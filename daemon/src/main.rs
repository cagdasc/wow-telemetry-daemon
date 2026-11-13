use std::error::Error;
use wow_telemetry_daemon::{cli, pipeline};

struct StderrLogger;

impl log::Log for StderrLogger {
    fn enabled(&self, metadata: &log::Metadata) -> bool {
        metadata.level() <= log::Level::Info
    }

    fn log(&self, record: &log::Record) {
        if self.enabled(record.metadata()) {
            eprintln!("{}", record.args());
        }
    }

    fn flush(&self) {}
}

static LOGGER: StderrLogger = StderrLogger;

fn init_logger() {
    let _ = log::set_logger(&LOGGER).map(|()| log::set_max_level(log::LevelFilter::Info));
}

fn main() -> Result<(), Box<dyn Error>> {
    init_logger();
    let Some(config) = cli::from_env()? else {
        return Ok(());
    };
    pipeline::run_pipeline(config)
}
