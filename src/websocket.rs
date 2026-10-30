use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc::{self, SyncSender, TryRecvError, TrySendError};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use tungstenite::Message;

const CLIENT_QUEUE_SIZE: usize = 256;
const CLIENT_READ_TIMEOUT: Duration = Duration::from_millis(50);
const CLIENT_WRITE_TIMEOUT: Duration = Duration::from_secs(1);

type ClientId = usize;
type ClientSender = SyncSender<Arc<str>>;
type Clients = Arc<Mutex<HashMap<ClientId, ClientSender>>>;

pub struct WebSocketServer {
    address: SocketAddr,
    clients: Clients,
}

impl WebSocketServer {
    pub fn bind(address: SocketAddr) -> io::Result<Self> {
        let listener = TcpListener::bind(address)?;
        listener.set_nonblocking(true)?;
        let address = listener.local_addr()?;
        let clients = Arc::new(Mutex::new(HashMap::new()));
        let server_clients = Arc::clone(&clients);
        let next_id = Arc::new(AtomicUsize::new(0));
        let server_next_id = Arc::clone(&next_id);

        thread::Builder::new()
            .name("websocket-listener".into())
            .spawn(move || accept_loop(listener, server_clients, server_next_id))?;

        Ok(Self { address, clients })
    }

    pub fn address(&self) -> SocketAddr {
        self.address
    }

    pub fn broadcast(&self, payload: Arc<str>) -> io::Result<()> {
        let mut clients = self
            .clients
            .lock()
            .map_err(|_| io::Error::other("WebSocket client registry lock poisoned"))?;

        clients.retain(|id, sender| match sender.try_send(Arc::clone(&payload)) {
            Ok(()) => true,
            Err(TrySendError::Full(_)) => {
                eprintln!("dropping slow WebSocket client {id}: outbound queue is full");
                false
            }
            Err(TrySendError::Disconnected(_)) => false,
        });
        Ok(())
    }
}

fn accept_loop(listener: TcpListener, clients: Clients, next_id: Arc<AtomicUsize>) {
    loop {
        match listener.accept() {
            Ok((stream, _)) => {
                let id = next_id.fetch_add(1, Ordering::Relaxed);
                let clients = Arc::clone(&clients);
                if let Err(error) = thread::Builder::new()
                    .name(format!("websocket-client-{id}"))
                    .spawn(move || serve_client(stream, id, clients))
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

fn serve_client(stream: TcpStream, id: ClientId, clients: Clients) {
    if let Err(error) = serve_connection(stream, id, &clients) {
        eprintln!("WebSocket client {id} disconnected: {error}");
    }
    if let Ok(mut clients) = clients.lock() {
        clients.remove(&id);
    }
}

fn serve_connection(stream: TcpStream, id: ClientId, clients: &Clients) -> Result<(), String> {
    stream
        .set_read_timeout(Some(CLIENT_READ_TIMEOUT))
        .map_err(|error| error.to_string())?;
    stream
        .set_write_timeout(Some(CLIENT_WRITE_TIMEOUT))
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

    loop {
        match websocket.read() {
            Ok(Message::Close(_)) => return Ok(()),
            Ok(Message::Ping(_)) => websocket.flush().map_err(|error| error.to_string())?,
            Ok(_) => {}
            Err(tungstenite::Error::Io(error))
                if matches!(
                    error.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                ) => {}
            Err(error) => return Err(error.to_string()),
        }

        match receiver.try_recv() {
            Ok(payload) => websocket
                .send(Message::Text(payload.to_string().into()))
                .map_err(|error| error.to_string())?,
            Err(TryRecvError::Empty) => {}
            Err(TryRecvError::Disconnected) => return Ok(()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::WebSocketServer;
    use std::sync::Arc;

    #[test]
    fn broadcasts_event_json_to_connected_clients() {
        let server = WebSocketServer::bind("127.0.0.1:0".parse().unwrap()).unwrap();
        let (mut client, _) = tungstenite::connect(format!("ws://{}", server.address())).unwrap();

        server
            .broadcast(Arc::from(r#"{"event":"SPELL_DAMAGE"}"#))
            .unwrap();

        let message = client.read().unwrap().into_text().unwrap();
        assert_eq!(message, r#"{"event":"SPELL_DAMAGE"}"#);
    }
}
