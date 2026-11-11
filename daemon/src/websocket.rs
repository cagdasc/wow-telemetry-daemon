use std::collections::{HashMap, VecDeque};
use std::io;
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc::{self, SyncSender, TryRecvError};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crate::tailer::TailCommand;
use tungstenite::Message;

const CLIENT_QUEUE_SIZE: usize = 8192;
const CLIENT_DRAIN_BATCH: usize = 4096;
const REPLAY_BUFFER_BYTES: usize = 10 * 1024 * 1024;
const CLIENT_READ_TIMEOUT: Duration = Duration::from_millis(50);

type ClientId = usize;
type ClientSender = SyncSender<Frame>;
type Clients = Arc<Mutex<HashMap<ClientId, ClientSender>>>;
type SharedReplay = Arc<Mutex<ReplayBuffer>>;

#[derive(Clone)]
struct Frame {
    seq: u64,
    text: Arc<str>,
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

    fn push(&mut self, payload: &str) -> Frame {
        let seq = self.next_seq;
        self.next_seq += 1;
        let body = payload.strip_prefix('{').unwrap_or(payload);
        let separator = if body.starts_with('}') { "" } else { "," };
        let frame = Frame {
            seq,
            text: Arc::from(format!(r#"{{"seq":{seq}{separator}{body}"#)),
        };
        self.bytes += frame.text.len();
        self.frames.push_back(frame.clone());
        while self.bytes > self.capacity && self.frames.len() > 1 {
            if let Some(evicted) = self.frames.pop_front() {
                self.bytes -= evicted.text.len();
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
    clients: Clients,
    replay: SharedReplay,
}

impl WebSocketServer {
    #[cfg(test)]
    pub fn bind(address: SocketAddr) -> io::Result<Self> {
        Self::bind_inner(address, None)
    }

    pub fn bind_with_clear_log(
        address: SocketAddr,
        clear_log_sender: SyncSender<TailCommand>,
    ) -> io::Result<Self> {
        Self::bind_inner(address, Some(clear_log_sender))
    }

    fn bind_inner(
        address: SocketAddr,
        clear_log_sender: Option<SyncSender<TailCommand>>,
    ) -> io::Result<Self> {
        let listener = TcpListener::bind(address)?;
        listener.set_nonblocking(true)?;
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
                    server_clients,
                    server_replay,
                    server_next_id,
                    clear_log_sender,
                )
            })?;

        Ok(Self {
            address,
            clients,
            replay,
        })
    }

    pub fn address(&self) -> SocketAddr {
        self.address
    }

    /// Drops buffered frames so a reconnecting client is not sent events the user cleared.
    pub fn clear_replay(&self) -> io::Result<()> {
        self.replay
            .lock()
            .map_err(|_| io::Error::other("WebSocket replay buffer lock poisoned"))?
            .clear();
        Ok(())
    }

    pub fn broadcast(&self, payload: Arc<str>) -> io::Result<()> {
        let frame = self
            .replay
            .lock()
            .map_err(|_| io::Error::other("WebSocket replay buffer lock poisoned"))?
            .push(&payload);
        let clients = self
            .clients
            .lock()
            .map_err(|_| io::Error::other("WebSocket client registry lock poisoned"))?;
        let senders: Vec<_> = clients
            .iter()
            .map(|(id, sender)| (*id, sender.clone()))
            .collect();
        drop(clients);

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
                    .spawn(move || serve_client(stream, id, clients, replay, clear_log_sender))
                {
                    eprintln!("failed to start WebSocket client thread: {error}");
                }
            }
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(5));
            }
            Err(error) => {
                eprintln!("WebSocket listener failed to accept a client: {error}");
                thread::sleep(Duration::from_millis(250));
            }
        }
    }
}

fn serve_client(
    stream: TcpStream,
    id: ClientId,
    clients: Clients,
    replay: SharedReplay,
    clear_log_sender: Option<SyncSender<TailCommand>>,
) {
    if let Err(error) = serve_connection(stream, id, &clients, &replay, clear_log_sender.as_ref()) {
        eprintln!("WebSocket client {id} disconnected: {error}");
    }
    if let Ok(mut clients) = clients.lock() {
        clients.remove(&id);
    }
}

fn serve_connection(
    stream: TcpStream,
    id: ClientId,
    clients: &Clients,
    replay: &SharedReplay,
    clear_log_sender: Option<&SyncSender<TailCommand>>,
) -> Result<(), String> {
    stream
        .set_read_timeout(Some(CLIENT_READ_TIMEOUT))
        .map_err(|error| error.to_string())?;
    stream
        .set_nodelay(true)
        .map_err(|error| error.to_string())?;

    let mut websocket = tungstenite::accept(stream).map_err(|error| error.to_string())?;
    let (sender, receiver) = mpsc::sync_channel(CLIENT_QUEUE_SIZE);
    clients
        .lock()
        .map_err(|_| "WebSocket client registry lock poisoned".to_owned())?
        .insert(id, sender);

    let hello = {
        let replay = replay
            .lock()
            .map_err(|_| "WebSocket replay buffer lock poisoned".to_owned())?;
        serde_json::json!({
            "control": "HELLO",
            "epoch": replay.epoch,
            "last_seq": replay.last_seq()
        })
    };
    send_json(&mut websocket, &hello)?;

    // Frames queued before a resume replay are already covered by the replay.
    let mut skip_through = 0_u64;
    loop {
        match websocket.read() {
            Ok(Message::Close(_)) => return Ok(()),
            Ok(Message::Ping(_)) => websocket.flush().map_err(|error| error.to_string())?,
            Ok(Message::Text(text)) => {
                handle_control_message(
                    &mut websocket,
                    &text,
                    clear_log_sender,
                    replay,
                    &mut skip_through,
                )?;
            }
            Ok(_) => {}
            Err(tungstenite::Error::Io(error))
                if matches!(
                    error.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                ) => {}
            Err(error) => return Err(error.to_string()),
        }
        let mut wrote = false;
        for _ in 0..CLIENT_DRAIN_BATCH {
            match receiver.try_recv() {
                Ok(frame) if frame.seq <= skip_through => {}
                Ok(frame) => {
                    websocket
                        .write(Message::Text(frame.text.to_string().into()))
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

fn handle_control_message(
    websocket: &mut tungstenite::WebSocket<TcpStream>,
    payload: &str,
    clear_log_sender: Option<&SyncSender<TailCommand>>,
    replay: &SharedReplay,
    skip_through: &mut u64,
) -> Result<(), String> {
    let response = match serde_json::from_str::<serde_json::Value>(payload) {
        Ok(request)
            if request.get("command").and_then(serde_json::Value::as_str) == Some("RESUME") =>
        {
            return handle_resume(websocket, &request, replay, skip_through);
        }
        Ok(request)
            if request.get("command").and_then(serde_json::Value::as_str) == Some("CLEAR_LOG") =>
        {
            let Some(clear_log_sender) = clear_log_sender else {
                return send_control_result(
                    websocket,
                    false,
                    Some("Log clearing is unavailable.".to_owned()),
                );
            };
            let (response_sender, response_receiver) = mpsc::sync_channel(1);
            if clear_log_sender
                .send(TailCommand::ClearLog(response_sender))
                .is_err()
            {
                Some(Err("Log tailer is unavailable.".to_owned()))
            } else {
                Some(
                    response_receiver
                        .recv_timeout(Duration::from_secs(30))
                        .map_err(|error| format!("Log clear did not finish: {error}"))
                        .and_then(|result| result),
                )
            }
        }
        Ok(_) => Some(Err("Unsupported WebSocket command.".to_owned())),
        Err(error) => Some(Err(format!("Invalid command JSON: {error}"))),
    };

    match response {
        Some(Ok(())) => send_control_result(websocket, true, None),
        Some(Err(error)) => send_control_result(websocket, false, Some(error)),
        None => Ok(()),
    }
}

fn handle_resume(
    websocket: &mut tungstenite::WebSocket<TcpStream>,
    request: &serde_json::Value,
    replay: &SharedReplay,
    skip_through: &mut u64,
) -> Result<(), String> {
    let epoch = request.get("epoch").and_then(serde_json::Value::as_u64);
    let from_seq = request.get("from_seq").and_then(serde_json::Value::as_u64);
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
                let gap = (first_missed < oldest).then(|| {
                    serde_json::json!({
                        "control": "REPLAY_GAP",
                        "reason": "BUFFER_EVICTED",
                        "missed_from": first_missed,
                        "available_from": oldest
                    })
                });
                *skip_through = replay.last_seq();
                (frames, gap)
            }
            (Some(_), Some(_)) => (
                Vec::new(),
                Some(serde_json::json!({
                    "control": "REPLAY_GAP",
                    "reason": "EPOCH_CHANGED"
                })),
            ),
            _ => (
                Vec::new(),
                Some(serde_json::json!({
                    "control": "REPLAY_GAP",
                    "reason": "INVALID_RESUME"
                })),
            ),
        }
    };
    if let Some(gap) = gap {
        send_json(websocket, &gap)?;
    }
    for frame in frames {
        websocket
            .write(Message::Text(frame.text.to_string().into()))
            .map_err(|error| error.to_string())?;
    }
    websocket.flush().map_err(|error| error.to_string())
}

fn send_json(
    websocket: &mut tungstenite::WebSocket<TcpStream>,
    value: &serde_json::Value,
) -> Result<(), String> {
    websocket
        .send(Message::Text(value.to_string().into()))
        .map_err(|error| error.to_string())
}

fn send_control_result(
    websocket: &mut tungstenite::WebSocket<TcpStream>,
    success: bool,
    message: Option<String>,
) -> Result<(), String> {
    let mut response = serde_json::json!({
        "control": "CLEAR_LOG_RESULT",
        "success": success
    });
    if let Some(message) = message {
        response["message"] = serde_json::Value::String(message);
    }
    websocket
        .send(Message::Text(response.to_string().into()))
        .map_err(|error| error.to_string())
}

#[cfg(test)]
mod tests {
    use super::{ClientSender, Frame, ReplayBuffer, WebSocketServer};
    use crate::tailer::TailCommand;
    use std::collections::HashMap;
    use std::net::TcpStream;
    use std::sync::Arc;
    use std::sync::mpsc;
    use std::thread;
    use std::time::Duration;
    use tungstenite::{Message, WebSocket, stream::MaybeTlsStream};

    fn read_json(client: &mut WebSocket<MaybeTlsStream<TcpStream>>) -> serde_json::Value {
        serde_json::from_str(&client.read().unwrap().into_text().unwrap()).unwrap()
    }

    #[test]
    fn broadcasts_event_json_to_connected_clients() {
        let server = WebSocketServer::bind("127.0.0.1:0".parse().unwrap()).unwrap();
        let (mut client, _) = tungstenite::connect(format!("ws://{}", server.address())).unwrap();
        assert_eq!(read_json(&mut client)["control"], "HELLO");

        server
            .broadcast(Arc::from(r#"{"event":"SPELL_DAMAGE"}"#))
            .unwrap();
        server
            .broadcast(Arc::from(r#"{"control":"LOG_CAUGHT_UP"}"#))
            .unwrap();

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
    fn responds_to_client_ping_frames() {
        let server = WebSocketServer::bind("127.0.0.1:0".parse().unwrap()).unwrap();
        let (mut client, _) = tungstenite::connect(format!("ws://{}", server.address())).unwrap();
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
        let server =
            WebSocketServer::bind_with_clear_log("127.0.0.1:0".parse().unwrap(), clear_sender)
                .unwrap();
        let (mut client, _) = tungstenite::connect(format!("ws://{}", server.address())).unwrap();

        client
            .send(Message::Text(r#"{"command":"CLEAR_LOG"}"#.into()))
            .unwrap();
        let response_sender = match clear_receiver
            .recv_timeout(Duration::from_secs(1))
            .expect("receive clear command")
        {
            TailCommand::ClearLog(response_sender) => response_sender,
        };
        response_sender.send(Ok(())).unwrap();

        assert_eq!(read_json(&mut client)["control"], "HELLO");
        let response = read_json(&mut client);
        assert_eq!(response["control"], "CLEAR_LOG_RESULT");
        assert_eq!(response["success"], true);
    }

    #[test]
    fn waits_for_a_slow_clients_queue_instead_of_dropping_it() {
        let server = Arc::new(WebSocketServer::bind("127.0.0.1:0".parse().unwrap()).unwrap());
        let (sender, receiver) = mpsc::sync_channel(1);
        sender
            .send(Frame {
                seq: 1,
                text: Arc::from("first"),
            })
            .unwrap();
        let clients: HashMap<usize, ClientSender> = HashMap::from([(0, sender)]);
        *server.clients.lock().unwrap() = clients;

        let broadcast_server = Arc::clone(&server);
        let broadcast = thread::spawn(move || {
            broadcast_server.broadcast(Arc::from(r#"{"n":2}"#)).unwrap();
        });

        assert!(receiver.recv_timeout(Duration::from_secs(1)).is_ok());
        assert_eq!(
            receiver
                .recv_timeout(Duration::from_secs(1))
                .unwrap()
                .text
                .as_ref(),
            r#"{"seq":1,"n":2}"#
        );
        broadcast.join().unwrap();
        assert!(server.clients.lock().unwrap().contains_key(&0));
    }

    #[test]
    fn replay_buffer_evicts_oldest_frames_over_capacity() {
        let mut replay = ReplayBuffer::new(60);
        for _ in 0..5 {
            replay.push(r#"{"a":1}"#);
        }
        assert_eq!(replay.last_seq(), 5);
        assert!(replay.oldest_seq() > 1);
        assert!(replay.bytes <= 60);
    }

    #[test]
    fn resume_replays_missed_frames_in_order() {
        let server = WebSocketServer::bind("127.0.0.1:0".parse().unwrap()).unwrap();
        let (mut first, _) = tungstenite::connect(format!("ws://{}", server.address())).unwrap();
        let hello = read_json(&mut first);
        server.broadcast(Arc::from(r#"{"n":1}"#)).unwrap();
        assert_eq!(read_json(&mut first)["seq"], 1);
        drop(first);
        server.broadcast(Arc::from(r#"{"n":2}"#)).unwrap();
        server.broadcast(Arc::from(r#"{"n":3}"#)).unwrap();

        let (mut second, _) = tungstenite::connect(format!("ws://{}", server.address())).unwrap();
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
        server.broadcast(Arc::from(r#"{"n":4}"#)).unwrap();
        assert_eq!(read_json(&mut second)["seq"], 4);
    }

    #[test]
    fn resume_with_unknown_epoch_reports_gap() {
        let server = WebSocketServer::bind("127.0.0.1:0".parse().unwrap()).unwrap();
        let (mut client, _) = tungstenite::connect(format!("ws://{}", server.address())).unwrap();
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
        let server = WebSocketServer::bind("127.0.0.1:0".parse().unwrap()).unwrap();
        let (mut first, _) = tungstenite::connect(format!("ws://{}", server.address())).unwrap();
        let hello = read_json(&mut first);
        drop(first);
        server.broadcast(Arc::from(r#"{"n":1}"#)).unwrap();
        server.clear_replay().unwrap();
        server.broadcast(Arc::from(r#"{"n":2}"#)).unwrap();

        let (mut second, _) = tungstenite::connect(format!("ws://{}", server.address())).unwrap();
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
}
