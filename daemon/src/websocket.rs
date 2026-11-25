use std::collections::{HashMap, VecDeque};
use std::io;
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc::{self, SyncSender, TryRecvError};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crate::protocol::{Command, Control, ReplayGapReason, WireFormat, control_message};
use crate::tailer::TailCommand;
use tungstenite::Message;

const CLIENT_QUEUE_SIZE: usize = 8192;
const CLIENT_DRAIN_BATCH: usize = 4096;
const REPLAY_BUFFER_BYTES: usize = 10 * 1024 * 1024;
const CLIENT_READ_TIMEOUT: Duration = Duration::from_millis(50);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);

type ClientId = usize;
type ClientSender = SyncSender<Frame>;
type Clients = Arc<Mutex<HashMap<ClientId, ClientSender>>>;
type SharedReplay = Arc<Mutex<ReplayBuffer>>;
type Socket = tungstenite::WebSocket<TcpStream>;

/// A sequenced message. Cloning is a reference-count bump, not a payload copy.
#[derive(Clone)]
struct Frame {
    seq: u64,
    message: Message,
}

/// Recent sequenced frames kept so a reconnecting client can resume without loss.
struct ReplayBuffer {
    epoch: u64,
    next_seq: u64,
    capacity: usize,
    bytes: usize,
    cleared_through: u64,
    frames: VecDeque<Frame>,
}

impl ReplayBuffer {
    fn new(capacity: usize) -> Self {
        let epoch = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |elapsed| elapsed.as_millis() as u64);
        Self {
            epoch,
            next_seq: 1,
            capacity,
            bytes: 0,
            cleared_through: 0,
            frames: VecDeque::new(),
        }
    }

    fn next_seq(&self) -> u64 {
        self.next_seq
    }

    fn push(&mut self, message: Message) -> Frame {
        let frame = Frame {
            seq: self.next_seq,
            message,
        };
        self.next_seq += 1;
        self.bytes += frame.message.len();
        self.frames.push_back(frame.clone());
        while self.bytes > self.capacity && self.frames.len() > 1 {
            if let Some(evicted) = self.frames.pop_front() {
                self.bytes -= evicted.message.len();
            }
        }
        frame
    }

    fn clear(&mut self) {
        self.cleared_through = self.last_seq();
        self.frames.clear();
        self.bytes = 0;
    }

    fn last_seq(&self) -> u64 {
        self.next_seq - 1
    }

    fn oldest_seq(&self) -> u64 {
        self.frames.front().map_or(self.next_seq, |frame| frame.seq)
    }
}

pub struct WebSocketServer {
    address: SocketAddr,
    format: WireFormat,
    clients: Clients,
    replay: SharedReplay,
}

impl WebSocketServer {
    #[cfg(test)]
    pub fn bind(address: SocketAddr, format: WireFormat) -> io::Result<Self> {
        Self::bind_inner(address, format, None)
    }

    pub fn bind_with_clear_log(
        address: SocketAddr,
        format: WireFormat,
        clear_log_sender: SyncSender<TailCommand>,
    ) -> io::Result<Self> {
        Self::bind_inner(address, format, Some(clear_log_sender))
    }

    fn bind_inner(
        address: SocketAddr,
        format: WireFormat,
        clear_log_sender: Option<SyncSender<TailCommand>>,
    ) -> io::Result<Self> {
        let listener = TcpListener::bind(address)?;
        let address = listener.local_addr()?;
        let clients = Arc::new(Mutex::new(HashMap::new()));
        let replay = Arc::new(Mutex::new(ReplayBuffer::new(REPLAY_BUFFER_BYTES)));
        let server_replay = Arc::clone(&replay);
        let server_clients = Arc::clone(&clients);
        let next_id = Arc::new(AtomicUsize::new(0));
        let server_next_id = Arc::clone(&next_id);

        thread::Builder::new()
            .name("websocket-listener".into())
            .spawn(move || {
                accept_loop(
                    listener,
                    format,
                    server_clients,
                    server_replay,
                    server_next_id,
                    clear_log_sender,
                )
            })?;

        Ok(Self {
            address,
            format,
            clients,
            replay,
        })
    }

    pub fn address(&self) -> SocketAddr {
        self.address
    }

    pub fn format(&self) -> WireFormat {
        self.format
    }

    pub fn has_clients(&self) -> bool {
        self.clients
            .lock()
            .is_ok_and(|clients| !clients.is_empty())
    }

    pub fn client_count(&self) -> usize {
        self.clients.lock().map_or(0, |clients| clients.len())
    }

    /// Drops buffered frames so a reconnecting client is not sent events the user cleared.
    pub fn clear_replay(&self) -> io::Result<()> {
        self.replay
            .lock()
            .map_err(|_| io::Error::other("WebSocket replay buffer lock poisoned"))?
            .clear();
        Ok(())
    }

    pub fn broadcast_control(&self, control: &Control) -> io::Result<()> {
        let format = self.format;
        self.broadcast_with::<std::convert::Infallible>(|seq| {
            Ok(control_message(format, Some(seq), control))
        })
    }

    /// Builds the next sequenced frame, stores it for replay, and queues it for every client.
    pub fn broadcast_with<E>(&self, build: impl FnOnce(u64) -> Result<Message, E>) -> io::Result<()>
    where
        E: std::error::Error + Send + Sync + 'static,
    {
        let frame = {
            let mut replay = self
                .replay
                .lock()
                .map_err(|_| io::Error::other("WebSocket replay buffer lock poisoned"))?;
            let message = build(replay.next_seq()).map_err(io::Error::other)?;
            replay.push(message)
        };
        let senders: Vec<_> = self
            .clients
            .lock()
            .map_err(|_| io::Error::other("WebSocket client registry lock poisoned"))?
            .iter()
            .map(|(id, sender)| (*id, sender.clone()))
            .collect();

        let mut disconnected = Vec::new();
        for (id, sender) in senders {
            if sender.send(frame.clone()).is_err() {
                disconnected.push(id);
            }
        }

        if !disconnected.is_empty() {
            let mut clients = self
                .clients
                .lock()
                .map_err(|_| io::Error::other("WebSocket client registry lock poisoned"))?;
            for id in disconnected {
                clients.remove(&id);
            }
        }
        Ok(())
    }
}

fn accept_loop(
    listener: TcpListener,
    format: WireFormat,
    clients: Clients,
    replay: SharedReplay,
    next_id: Arc<AtomicUsize>,
    clear_log_sender: Option<SyncSender<TailCommand>>,
) {
    loop {
        match listener.accept() {
            Ok((stream, _)) => {
                let id = next_id.fetch_add(1, Ordering::Relaxed);
                let clients = Arc::clone(&clients);
                let replay = Arc::clone(&replay);
                let clear_log_sender = clear_log_sender.clone();
                if let Err(error) = thread::Builder::new()
                    .name(format!("websocket-client-{id}"))
                    .spawn(move || {
                        serve_client(stream, id, format, clients, replay, clear_log_sender)
                    })
                {
                    log::error!("failed to start WebSocket client thread: {error}");
                }
            }
            Err(error) => {
                log::error!("WebSocket listener failed to accept a client: {error}");
                thread::sleep(Duration::from_millis(250));
            }
        }
    }
}

fn serve_client(
    stream: TcpStream,
    id: ClientId,
    format: WireFormat,
    clients: Clients,
    replay: SharedReplay,
    clear_log_sender: Option<SyncSender<TailCommand>>,
) {
    if let Err(error) = serve_connection(
        stream,
        id,
        format,
        &clients,
        &replay,
        clear_log_sender.as_ref(),
    ) {
        log::info!("WebSocket client {id} disconnected: {error}");
    }
    if let Ok(mut clients) = clients.lock() {
        clients.remove(&id);
    }
}

fn serve_connection(
    stream: TcpStream,
    id: ClientId,
    format: WireFormat,
    clients: &Clients,
    replay: &SharedReplay,
    clear_log_sender: Option<&SyncSender<TailCommand>>,
) -> Result<(), String> {
    stream
        .set_read_timeout(Some(HANDSHAKE_TIMEOUT))
        .map_err(|error| error.to_string())?;
    stream
        .set_nodelay(true)
        .map_err(|error| error.to_string())?;

    let mut websocket = tungstenite::accept(stream).map_err(|error| error.to_string())?;
    // The short timeout drives the poll loop below; the handshake needs a longer one.
    websocket
        .get_ref()
        .set_read_timeout(Some(CLIENT_READ_TIMEOUT))
        .map_err(|error| error.to_string())?;
    let (sender, receiver) = mpsc::sync_channel(CLIENT_QUEUE_SIZE);
    clients
        .lock()
        .map_err(|_| "WebSocket client registry lock poisoned".to_owned())?
        .insert(id, sender);

    let hello = {
        let replay = replay
            .lock()
            .map_err(|_| "WebSocket replay buffer lock poisoned".to_owned())?;
        Control::Hello {
            epoch: replay.epoch,
            last_seq: replay.last_seq(),
        }
    };
    send_control(&mut websocket, format, &hello)?;

    // Frames queued before a resume replay are already covered by the replay.
    let mut skip_through = 0_u64;
    loop {
        let command = match websocket.read() {
            Ok(Message::Close(_)) => return Ok(()),
            Ok(Message::Ping(_)) => {
                websocket.flush().map_err(|error| error.to_string())?;
                None
            }
            Ok(Message::Text(text)) => Some(Command::from_json(&text)),
            Ok(Message::Binary(bytes)) => Some(Command::from_protobuf(&bytes)),
            Ok(_) => None,
            Err(tungstenite::Error::Io(error))
                if matches!(
                    error.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                ) =>
            {
                None
            }
            Err(error) => return Err(error.to_string()),
        };
        if let Some(command) = command {
            handle_command(
                &mut websocket,
                format,
                command,
                clear_log_sender,
                replay,
                &mut skip_through,
            )?;
        }

        let mut wrote = false;
        for _ in 0..CLIENT_DRAIN_BATCH {
            match receiver.try_recv() {
                Ok(frame) if frame.seq <= skip_through => {}
                Ok(frame) => {
                    websocket
                        .write(frame.message)
                        .map_err(|error| error.to_string())?;
                    wrote = true;
                }
                Err(TryRecvError::Empty) => break,
                Err(TryRecvError::Disconnected) => return Ok(()),
            }
        }
        if wrote {
            websocket.flush().map_err(|error| error.to_string())?;
        }
    }
}

fn handle_command(
    websocket: &mut Socket,
    format: WireFormat,
    command: Result<Command, String>,
    clear_log_sender: Option<&SyncSender<TailCommand>>,
    replay: &SharedReplay,
    skip_through: &mut u64,
) -> Result<(), String> {
    let result = match command {
        Ok(Command::Resume { epoch, from_seq }) => {
            return handle_resume(websocket, format, epoch, from_seq, replay, skip_through);
        }
        Ok(Command::ClearLog) => clear_log(clear_log_sender),
        Err(error) => Err(error),
    };
    let control = match result {
        Ok(()) => Control::ClearLogResult {
            success: true,
            message: None,
        },
        Err(error) => Control::ClearLogResult {
            success: false,
            message: Some(error),
        },
    };
    send_control(websocket, format, &control)
}

fn clear_log(clear_log_sender: Option<&SyncSender<TailCommand>>) -> Result<(), String> {
    let Some(clear_log_sender) = clear_log_sender else {
        return Err("Log clearing is unavailable.".to_owned());
    };
    let (response_sender, response_receiver) = mpsc::sync_channel(1);
    clear_log_sender
        .send(TailCommand::ClearLog(response_sender))
        .map_err(|_| "Log tailer is unavailable.".to_owned())?;
    response_receiver
        .recv_timeout(Duration::from_secs(30))
        .map_err(|error| format!("Log clear did not finish: {error}"))?
}

fn handle_resume(
    websocket: &mut Socket,
    format: WireFormat,
    epoch: Option<u64>,
    from_seq: Option<u64>,
    replay: &SharedReplay,
    skip_through: &mut u64,
) -> Result<(), String> {
    let (frames, gap) = {
        let replay = replay
            .lock()
            .map_err(|_| "WebSocket replay buffer lock poisoned".to_owned())?;
        match (epoch, from_seq) {
            (Some(epoch), Some(from_seq)) if epoch == replay.epoch => {
                let frames: Vec<Frame> = replay
                    .frames
                    .iter()
                    .filter(|frame| frame.seq > from_seq)
                    .cloned()
                    .collect();
                let oldest = replay.oldest_seq();
                let first_missed = (from_seq + 1).max(replay.cleared_through + 1);
                let gap = (first_missed < oldest).then_some(ReplayGapReason::BufferEvicted {
                    missed_from: first_missed,
                    available_from: oldest,
                });
                *skip_through = replay.last_seq();
                (frames, gap)
            }
            (Some(_), Some(_)) => (Vec::new(), Some(ReplayGapReason::EpochChanged)),
            _ => (Vec::new(), Some(ReplayGapReason::InvalidResume)),
        }
    };
    if let Some(reason) = gap {
        send_control(websocket, format, &Control::ReplayGap(reason))?;
    }
    for frame in frames {
        websocket
            .write(frame.message)
            .map_err(|error| error.to_string())?;
    }
    websocket.flush().map_err(|error| error.to_string())
}

fn send_control(
    websocket: &mut Socket,
    format: WireFormat,
    control: &Control,
) -> Result<(), String> {
    websocket
        .send(control_message(format, None, control))
        .map_err(|error| error.to_string())
}

#[cfg(test)]
mod tests {
    use super::{ClientSender, Frame, ReplayBuffer, WebSocketServer};
    use crate::pb;
    use crate::protocol::{Control, WireFormat, json_with_seq};
    use crate::tailer::TailCommand;
    use prost::Message as _;
    use std::collections::HashMap;
    use std::convert::Infallible;
    use std::net::TcpStream;
    use std::sync::Arc;
    use std::sync::mpsc;
    use std::thread;
    use std::time::Duration;
    use tungstenite::{Message, WebSocket, stream::MaybeTlsStream};

    type Client = WebSocket<MaybeTlsStream<TcpStream>>;

    fn json_server() -> WebSocketServer {
        WebSocketServer::bind("127.0.0.1:0".parse().unwrap(), WireFormat::Json).unwrap()
    }

    fn connect(server: &WebSocketServer) -> Client {
        tungstenite::connect(format!("ws://{}", server.address()))
            .unwrap()
            .0
    }

    fn read_json(client: &mut Client) -> serde_json::Value {
        serde_json::from_str(&client.read().unwrap().into_text().unwrap()).unwrap()
    }

    fn read_envelope(client: &mut Client) -> pb::Envelope {
        pb::Envelope::decode(client.read().unwrap().into_data().as_ref()).unwrap()
    }

    fn broadcast_json(server: &WebSocketServer, payload: &str) {
        server
            .broadcast_with::<Infallible>(|seq| {
                Ok(Message::Text(json_with_seq(seq, payload).into()))
            })
            .unwrap();
    }

    #[test]
    fn broadcasts_event_json_to_connected_clients() {
        let server = json_server();
        let mut client = connect(&server);
        assert_eq!(read_json(&mut client)["control"], "HELLO");

        broadcast_json(&server, r#"{"event":"SPELL_DAMAGE"}"#);
        server.broadcast_control(&Control::LogCaughtUp).unwrap();

        assert_eq!(
            client.read().unwrap().into_text().unwrap(),
            r#"{"seq":1,"event":"SPELL_DAMAGE"}"#
        );
        assert_eq!(
            client.read().unwrap().into_text().unwrap(),
            r#"{"seq":2,"control":"LOG_CAUGHT_UP"}"#
        );
    }

    #[test]
    fn protobuf_server_sends_binary_hello_and_sequenced_control() {
        let server =
            WebSocketServer::bind("127.0.0.1:0".parse().unwrap(), WireFormat::Protobuf).unwrap();
        let mut client = connect(&server);
        let hello = read_envelope(&mut client);
        assert_eq!(hello.seq, 0);
        let Some(pb::envelope::Body::Control(pb::Control {
            kind: Some(pb::control::Kind::Hello(hello)),
        })) = hello.body
        else {
            panic!("expected HELLO");
        };
        assert_eq!(hello.protocol_version, 1);

        server.broadcast_control(&Control::LogCaughtUp).unwrap();
        let caught_up = read_envelope(&mut client);
        assert_eq!(caught_up.seq, 1);
        assert!(matches!(
            caught_up.body,
            Some(pb::envelope::Body::Control(pb::Control {
                kind: Some(pb::control::Kind::LogCaughtUp(_))
            }))
        ));

        let resume = pb::Command {
            kind: Some(pb::command::Kind::Resume(pb::Resume {
                epoch: hello.epoch,
                from_seq: 0,
            })),
        };
        client
            .send(Message::Binary(resume.encode_to_vec().into()))
            .unwrap();
        assert_eq!(read_envelope(&mut client).seq, 1);
    }

    #[test]
    fn responds_to_client_ping_frames() {
        let server = json_server();
        let mut client = connect(&server);
        let payload = vec![1, 2, 3];

        client.send(Message::Ping(payload.clone().into())).unwrap();

        loop {
            match client.read().unwrap() {
                Message::Pong(response) => {
                    assert_eq!(response.as_ref(), payload);
                    break;
                }
                Message::Text(_) => {}
                other => panic!("unexpected message {other:?}"),
            }
        }
    }

    #[test]
    fn accepts_clear_log_command_and_returns_result() {
        let (clear_sender, clear_receiver) = mpsc::sync_channel(1);
        let server = WebSocketServer::bind_with_clear_log(
            "127.0.0.1:0".parse().unwrap(),
            WireFormat::Json,
            clear_sender,
        )
        .unwrap();
        let mut client = connect(&server);

        client
            .send(Message::Text(r#"{"command":"CLEAR_LOG"}"#.into()))
            .unwrap();
        let TailCommand::ClearLog(response_sender) = clear_receiver
            .recv_timeout(Duration::from_secs(1))
            .expect("receive clear command");
        response_sender.send(Ok(())).unwrap();

        assert_eq!(read_json(&mut client)["control"], "HELLO");
        let response = read_json(&mut client);
        assert_eq!(response["control"], "CLEAR_LOG_RESULT");
        assert_eq!(response["success"], true);
    }

    #[test]
    fn waits_for_a_slow_clients_queue_instead_of_dropping_it() {
        let server = Arc::new(json_server());
        let (sender, receiver) = mpsc::sync_channel(1);
        sender
            .send(Frame {
                seq: 1,
                message: Message::Text("first".into()),
            })
            .unwrap();
        let clients: HashMap<usize, ClientSender> = HashMap::from([(0, sender)]);
        *server.clients.lock().unwrap() = clients;

        let broadcast_server = Arc::clone(&server);
        let broadcast = thread::spawn(move || {
            broadcast_json(&broadcast_server, r#"{"n":2}"#);
        });

        assert!(receiver.recv_timeout(Duration::from_secs(1)).is_ok());
        let second = receiver.recv_timeout(Duration::from_secs(1)).unwrap();
        assert_eq!(
            second.message.into_text().unwrap().as_str(),
            r#"{"seq":1,"n":2}"#
        );
        broadcast.join().unwrap();
        assert!(server.clients.lock().unwrap().contains_key(&0));
    }

    #[test]
    fn replay_buffer_evicts_oldest_frames_over_capacity() {
        let mut replay = ReplayBuffer::new(20);
        for _ in 0..5 {
            replay.push(Message::Text(r#"{"a":1}"#.into()));
        }
        assert_eq!(replay.last_seq(), 5);
        assert!(replay.oldest_seq() > 1);
        assert!(replay.bytes <= 20);
    }

    #[test]
    fn resume_replays_missed_frames_in_order() {
        let server = json_server();
        let mut first = connect(&server);
        let hello = read_json(&mut first);
        broadcast_json(&server, r#"{"n":1}"#);
        assert_eq!(read_json(&mut first)["seq"], 1);
        drop(first);
        broadcast_json(&server, r#"{"n":2}"#);
        broadcast_json(&server, r#"{"n":3}"#);

        let mut second = connect(&server);
        assert_eq!(read_json(&mut second)["control"], "HELLO");
        second
            .send(Message::Text(
                serde_json::json!({"command": "RESUME", "epoch": hello["epoch"], "from_seq": 1})
                    .to_string()
                    .into(),
            ))
            .unwrap();
        assert_eq!(read_json(&mut second)["seq"], 2);
        assert_eq!(read_json(&mut second)["seq"], 3);
        broadcast_json(&server, r#"{"n":4}"#);
        assert_eq!(read_json(&mut second)["seq"], 4);
    }

    #[test]
    fn resume_with_unknown_epoch_reports_gap() {
        let server = json_server();
        let mut client = connect(&server);
        assert_eq!(read_json(&mut client)["control"], "HELLO");
        client
            .send(Message::Text(
                r#"{"command":"RESUME","epoch":1,"from_seq":5}"#.into(),
            ))
            .unwrap();
        let gap = read_json(&mut client);
        assert_eq!(gap["control"], "REPLAY_GAP");
        assert_eq!(gap["reason"], "EPOCH_CHANGED");
    }

    #[test]
    fn clearing_replay_drops_old_frames_without_reporting_a_gap() {
        let server = json_server();
        let mut first = connect(&server);
        let hello = read_json(&mut first);
        drop(first);
        broadcast_json(&server, r#"{"n":1}"#);
        server.clear_replay().unwrap();
        broadcast_json(&server, r#"{"n":2}"#);

        let mut second = connect(&server);
        assert_eq!(read_json(&mut second)["control"], "HELLO");
        second
            .send(Message::Text(
                serde_json::json!({"command": "RESUME", "epoch": hello["epoch"], "from_seq": 0})
                    .to_string()
                    .into(),
            ))
            .unwrap();
        let replayed = read_json(&mut second);
        assert_eq!(replayed["seq"], 2);
    }
    #[test]
    fn has_clients_reports_connection_status() {
        let server = json_server();
        assert!(!server.has_clients());
        assert_eq!(server.client_count(), 0);

        let mut client = connect(&server);
        assert_eq!(read_json(&mut client)["control"], "HELLO");
        assert!(server.has_clients());
        assert_eq!(server.client_count(), 1);

        drop(client);
        // Client thread removes itself on read timeout or write failure
        let mut disconnected = false;
        for _ in 0..20 {
            thread::sleep(Duration::from_millis(20));
            let _ = server.broadcast_control(&Control::LogCaughtUp);
            if !server.has_clients() {
                disconnected = true;
                break;
            }
        }
        assert!(disconnected);
        assert_eq!(server.client_count(), 0);
    }
}
