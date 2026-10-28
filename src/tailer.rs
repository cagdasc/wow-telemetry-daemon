use std::fs::{self, File};
use std::io::{self, BufRead, BufReader, Seek, SeekFrom};
use std::path::PathBuf;
use std::sync::mpsc::SyncSender;
use std::thread;
use std::time::Duration;

const EOF_SLEEP: Duration = Duration::from_millis(2);

pub enum TailMessage {
    Line(Vec<u8>),
    Error(io::Error),
}

pub fn tail_file(path: PathBuf, sender: SyncSender<TailMessage>) {
    if let Err(error) = tail_loop(&path, &sender) {
        let _ = sender.send(TailMessage::Error(error));
    }
}

fn tail_loop(path: &PathBuf, sender: &SyncSender<TailMessage>) -> io::Result<()> {
    let file = File::open(path)?;
    let mut reader = BufReader::new(file);
    reader.seek(SeekFrom::End(0))?;
    let mut pending = Vec::new();

    loop {
        let bytes_read = reader.read_until(b'\n', &mut pending)?;
        if bytes_read > 0 {
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
                continue;
            }
        }

        thread::sleep(EOF_SLEEP);
    }
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
