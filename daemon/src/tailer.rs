use std::fs::{self, File, OpenOptions};
use std::io::{self, BufRead, BufReader, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::sync::mpsc::{Receiver, SyncSender};
use std::thread;
use std::time::Duration;

const EOF_SLEEP: Duration = Duration::from_millis(2);
const MISSING_FILE_RETRY: Duration = Duration::from_millis(500);

pub enum TailMessage {
    Line(Vec<u8>),
    CaughtUp,
    LogCleared(SyncSender<Result<(), String>>),
    Error(io::Error),
}

pub enum TailCommand {
    ClearLog(SyncSender<Result<(), String>>),
}

pub fn tail_file(
    path: PathBuf,
    sender: SyncSender<TailMessage>,
    commands: Receiver<TailCommand>,
    start_at_end: bool,
) {
    if let Err(error) = tail_loop(&path, &sender, &commands, start_at_end) {
        let _ = sender.send(TailMessage::Error(error));
    }
}

fn tail_loop(
    path: &PathBuf,
    sender: &SyncSender<TailMessage>,
    commands: &Receiver<TailCommand>,
    start_at_end: bool,
) -> io::Result<()> {
    let mut reader = wait_for_reader(path, start_at_end)?;
    let mut pending = Vec::new();
    let mut caught_up = false;

    loop {
        while let Ok(TailCommand::ClearLog(response)) = commands.try_recv() {
            match truncate_log(path, &mut reader, &mut pending) {
                Ok(()) => {
                    caught_up = false;
                    sender
                        .send(TailMessage::LogCleared(response))
                        .map_err(|_| {
                            io::Error::new(io::ErrorKind::BrokenPipe, "event receiver closed")
                        })?;
                }
                Err(error) => {
                    let _ = response.send(Err(error.to_string()));
                }
            }
        }

        let bytes_read = reader.read_until(b'\n', &mut pending)?;
        if bytes_read > 0 {
            caught_up = false;
            if pending.last() == Some(&b'\n') {
                let line = without_leading_nuls(std::mem::take(&mut pending));
                if line.iter().any(|byte| !byte.is_ascii_whitespace()) {
                    sender.send(TailMessage::Line(line)).map_err(|_| {
                        io::Error::new(io::ErrorKind::BrokenPipe, "event receiver closed")
                    })?;
                }
            }
            continue;
        }

        if let Some(path_metadata) = metadata_if_available(path)? {
            let open_metadata = reader.get_ref().metadata()?;
            if file_was_replaced(&open_metadata, &path_metadata)
                || reader.stream_position()? > open_metadata.len()
            {
                // The replacement may vanish again before it can be opened; retry on the next pass.
                match File::open(path) {
                    Ok(file) => {
                        reader = BufReader::new(file);
                        pending.clear();
                        caught_up = false;
                        continue;
                    }
                    Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                    Err(error) => return Err(error),
                }
            }
        }

        if !caught_up {
            sender
                .send(TailMessage::CaughtUp)
                .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "event receiver closed"))?;
            caught_up = true;
        }

        thread::sleep(EOF_SLEEP);
    }
}

/// Truncates the log in place. WoW keeps writing at its old offset, so the file
/// may start with a NUL-filled gap; `without_leading_nuls` strips it from the first line.
fn truncate_log(
    path: &Path,
    reader: &mut BufReader<File>,
    pending: &mut Vec<u8>,
) -> io::Result<()> {
    OpenOptions::new().write(true).open(path)?.set_len(0)?;
    reader.seek(SeekFrom::Start(0))?;
    pending.clear();
    Ok(())
}

fn without_leading_nuls(mut line: Vec<u8>) -> Vec<u8> {
    let nuls = line.iter().take_while(|byte| **byte == 0).count();
    line.drain(..nuls);
    line
}

/// Waits for the log to exist, so the daemon can start before WoW creates it.
fn wait_for_reader(path: &Path, start_at_end: bool) -> io::Result<BufReader<File>> {
    let mut announced = false;
    loop {
        match open_reader(path, start_at_end) {
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                if !announced {
                    log::info!("waiting for combat log: {}", path.display());
                    announced = true;
                }
                thread::sleep(MISSING_FILE_RETRY);
            }
            result => return result,
        }
    }
}

fn open_reader(path: &Path, start_at_end: bool) -> io::Result<BufReader<File>> {
    let file = File::open(path)?;
    let mut reader = BufReader::new(file);
    if start_at_end {
        reader.seek(SeekFrom::End(0))?;
    }
    Ok(reader)
}

fn metadata_if_available(path: &PathBuf) -> io::Result<Option<fs::Metadata>> {
    match fs::metadata(path) {
        Ok(metadata) => Ok(Some(metadata)),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error),
    }
}

#[cfg(unix)]
fn file_was_replaced(open: &fs::Metadata, current: &fs::Metadata) -> bool {
    use std::os::unix::fs::MetadataExt;
    open.dev() != current.dev() || open.ino() != current.ino()
}

// Windows has no stable file-index API; a replaced file gets a new creation time.
#[cfg(windows)]
fn file_was_replaced(open: &fs::Metadata, current: &fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    open.creation_time() != current.creation_time()
}

#[cfg(not(any(unix, windows)))]
fn file_was_replaced(_open: &fs::Metadata, _current: &fs::Metadata) -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::OpenOptions;
    use std::io::{Read, Write};
    use std::sync::mpsc;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn from_start_continues_tailing_appended_lines() {
        let path = temporary_log_path();
        fs::write(&path, b"existing event\n").expect("write fixture");

        let mut from_end = open_reader(&path, true).expect("open from end");
        let mut end_contents = Vec::new();
        from_end
            .read_to_end(&mut end_contents)
            .expect("read fixture from end");
        assert!(end_contents.is_empty());

        let (sender, receiver) = mpsc::sync_channel(4);
        let tail_path = path.clone();
        let (_, commands) = mpsc::sync_channel(4);
        let tail_thread = thread::spawn(move || tail_file(tail_path, sender, commands, false));

        assert_eq!(receive_line(&receiver), b"existing event\n",);

        append_line(&path, b"later event\n");
        assert_eq!(receive_line(&receiver), b"later event\n");

        drop(receiver);
        append_line(&path, b"stop event\n");
        tail_thread.join().expect("join tailer thread");
        fs::remove_file(path).expect("remove fixture");
    }

    #[test]
    fn waits_for_a_missing_file_to_appear() {
        let path = temporary_log_path();

        let (sender, receiver) = mpsc::sync_channel(4);
        let tail_path = path.clone();
        let (_, commands) = mpsc::sync_channel(4);
        let tail_thread = thread::spawn(move || tail_file(tail_path, sender, commands, false));

        assert!(matches!(
            receiver.recv_timeout(Duration::from_millis(100)),
            Err(mpsc::RecvTimeoutError::Timeout)
        ));
        fs::write(&path, b"first event\n").expect("create fixture");
        assert_eq!(receive_line(&receiver), b"first event\n");

        drop(receiver);
        append_line(&path, b"stop event\n");
        tail_thread.join().expect("join tailer thread");
        fs::remove_file(path).expect("remove fixture");
    }

    #[test]
    fn from_start_waits_for_content_in_an_empty_file() {
        let path = temporary_log_path();
        File::create(&path).expect("create empty fixture");

        let (sender, receiver) = mpsc::sync_channel(4);
        let tail_path = path.clone();
        let (_, commands) = mpsc::sync_channel(4);
        let tail_thread = thread::spawn(move || tail_file(tail_path, sender, commands, false));

        receive_caught_up(&receiver);
        assert!(matches!(
            receiver.recv_timeout(Duration::from_millis(50)),
            Err(mpsc::RecvTimeoutError::Timeout)
        ));

        append_line(&path, b"later event\n");
        assert_eq!(receive_line(&receiver), b"later event\n");

        drop(receiver);
        append_line(&path, b"stop event\n");
        tail_thread.join().expect("join tailer thread");
        fs::remove_file(path).expect("remove fixture");
    }

    #[test]
    fn emits_caught_up_once_after_each_available_batch() {
        let path = temporary_log_path();
        File::create(&path).expect("create empty fixture");

        let (sender, receiver) = mpsc::sync_channel(4);
        let tail_path = path.clone();
        let (_, commands) = mpsc::sync_channel(4);
        let tail_thread = thread::spawn(move || tail_file(tail_path, sender, commands, false));

        receive_caught_up(&receiver);
        assert!(matches!(
            receiver.recv_timeout(Duration::from_millis(50)),
            Err(mpsc::RecvTimeoutError::Timeout)
        ));

        append_line(&path, b"later event\n");
        assert_eq!(receive_line(&receiver), b"later event\n");
        receive_caught_up(&receiver);
        assert!(matches!(
            receiver.recv_timeout(Duration::from_millis(50)),
            Err(mpsc::RecvTimeoutError::Timeout)
        ));

        drop(receiver);
        append_line(&path, b"stop event\n");
        tail_thread.join().expect("join tailer thread");
        fs::remove_file(path).expect("remove fixture");
    }

    #[test]
    fn clearing_log_truncates_file_and_ignores_nul_gap_from_stale_writer_offset() {
        let path = temporary_log_path();
        fs::write(&path, b"old event\npartial").expect("write fixture");
        let mut writer = OpenOptions::new()
            .read(true)
            .write(true)
            .open(&path)
            .expect("open fixture writer");
        writer.seek(SeekFrom::End(0)).expect("seek writer to end");

        let (sender, receiver) = mpsc::sync_channel(4);
        let (commands_sender, commands_receiver) = mpsc::sync_channel(4);
        let tail_path = path.clone();
        let tail_thread =
            thread::spawn(move || tail_file(tail_path, sender, commands_receiver, true));
        receive_caught_up(&receiver);

        let (response_sender, response_receiver) = mpsc::sync_channel(1);
        commands_sender
            .send(TailCommand::ClearLog(response_sender))
            .expect("send clear command");
        loop {
            match receiver
                .recv_timeout(Duration::from_secs(2))
                .expect("receive clear acknowledgement")
            {
                TailMessage::LogCleared(response) => {
                    response.send(Ok(())).expect("acknowledge clear");
                    break;
                }
                TailMessage::Line(_) | TailMessage::CaughtUp => {}
                TailMessage::Error(error) => panic!("tailer failed: {error}"),
            }
        }
        response_receiver
            .recv_timeout(Duration::from_secs(2))
            .expect("receive clear result")
            .expect("clear log");

        writer
            .write_all(b" suffix\nnew event\n")
            .expect("append fixture events");
        writer.flush().expect("flush fixture writer");
        assert_eq!(receive_line(&receiver), b" suffix\n");
        assert_eq!(receive_line(&receiver), b"new event\n");
        assert_eq!(
            fs::read(&path).expect("read log"),
            b"old event\npartial"
                .iter()
                .map(|_| 0)
                .chain(b" suffix\nnew event\n".iter().copied())
                .collect::<Vec<u8>>()
        );

        drop(writer);
        drop(receiver);
        append_line(&path, b"stop event\n");
        tail_thread.join().expect("join tailer thread");
        fs::remove_file(path).expect("remove fixture");
    }

    fn receive_line(receiver: &mpsc::Receiver<TailMessage>) -> Vec<u8> {
        loop {
            match receiver
                .recv_timeout(Duration::from_secs(2))
                .expect("receive tailed line")
            {
                TailMessage::Line(line) => return line,
                TailMessage::CaughtUp => {}
                TailMessage::LogCleared(response) => {
                    let _ = response.send(Ok(()));
                }
                TailMessage::Error(error) => panic!("tailer failed: {error}"),
            }
        }
    }

    fn receive_caught_up(receiver: &mpsc::Receiver<TailMessage>) {
        loop {
            match receiver
                .recv_timeout(Duration::from_secs(2))
                .expect("receive caught-up marker")
            {
                TailMessage::CaughtUp => return,
                TailMessage::Line(_) => {}
                TailMessage::LogCleared(response) => {
                    let _ = response.send(Ok(()));
                }
                TailMessage::Error(error) => panic!("tailer failed: {error}"),
            }
        }
    }

    fn append_line(path: &Path, line: &[u8]) {
        OpenOptions::new()
            .append(true)
            .open(path)
            .expect("open fixture for append")
            .write_all(line)
            .expect("append fixture line");
    }

    fn temporary_log_path() -> PathBuf {
        std::env::temp_dir().join(format!(
            "wow-telemetry-tailer-{}.log",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .expect("system clock before Unix epoch")
                .as_nanos()
        ))
    }
}
