use std::fs::{self, File};
use std::io::{self, BufRead, BufReader, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::sync::mpsc::SyncSender;
use std::thread;
use std::time::Duration;

const EOF_SLEEP: Duration = Duration::from_millis(2);

pub enum TailMessage {
    Line(Vec<u8>),
    CaughtUp,
    Error(io::Error),
}

pub fn tail_file(path: PathBuf, sender: SyncSender<TailMessage>, start_at_end: bool) {
    if let Err(error) = tail_loop(&path, &sender, start_at_end) {
        let _ = sender.send(TailMessage::Error(error));
    }
}

fn tail_loop(
    path: &PathBuf,
    sender: &SyncSender<TailMessage>,
    start_at_end: bool,
) -> io::Result<()> {
    let mut reader = open_reader(path, start_at_end)?;
    let mut pending = Vec::new();
    let mut caught_up = false;

    loop {
        let bytes_read = reader.read_until(b'\n', &mut pending)?;
        if bytes_read > 0 {
            caught_up = false;
            if pending.last() == Some(&b'\n') {
                sender
                    .send(TailMessage::Line(std::mem::take(&mut pending)))
                    .map_err(|_| {
                        io::Error::new(io::ErrorKind::BrokenPipe, "event receiver closed")
                    })?;
            }
            continue;
        }

        if let Some(path_metadata) = metadata_if_available(path)? {
            let open_metadata = reader.get_ref().metadata()?;
            if file_was_replaced(&open_metadata, &path_metadata)
                || reader.stream_position()? > open_metadata.len()
            {
                reader = BufReader::new(File::open(path)?);
                pending.clear();
                caught_up = false;
                continue;
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

#[cfg(not(unix))]
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
        let tail_thread = thread::spawn(move || tail_file(tail_path, sender, false));

        assert_eq!(receive_line(&receiver), b"existing event\n",);

        append_line(&path, b"later event\n");
        assert_eq!(receive_line(&receiver), b"later event\n");

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
        let tail_thread = thread::spawn(move || tail_file(tail_path, sender, false));

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
        let tail_thread = thread::spawn(move || tail_file(tail_path, sender, false));

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

    fn receive_line(receiver: &mpsc::Receiver<TailMessage>) -> Vec<u8> {
        loop {
            match receiver
                .recv_timeout(Duration::from_secs(2))
                .expect("receive tailed line")
            {
                TailMessage::Line(line) => return line,
                TailMessage::CaughtUp => {}
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
