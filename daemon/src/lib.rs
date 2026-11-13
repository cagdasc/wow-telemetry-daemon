pub mod cli;
pub mod parser;
pub mod pipeline;
pub mod proto_events;
pub mod protocol;
pub mod spec_events;
pub mod tailer;
pub mod typed_events;
pub mod websocket;

#[allow(
    clippy::all,
    clippy::pedantic,
    clippy::large_enum_variant,
    clippy::enum_variant_names
)]
pub(crate) mod pb {
    include!(concat!(env!("OUT_DIR"), "/wowtelemetry.v1.rs"));
}
