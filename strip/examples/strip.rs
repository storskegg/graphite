//! Strips one file: `cargo run --example strip -- IN OUT MIME [identifiers|display]`.

use graphite_strip::{Choice, strip};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let [_, input, output, mime, choice] = &args[..] else {
        eprintln!("usage: strip IN OUT MIME identifiers|display");
        std::process::exit(2);
    };
    let choice = match choice.as_str() {
        "identifiers" => Choice::RemoveLocationAndIdentifiers,
        "display" => Choice::KeepOnlyDisplay,
        "keep" => Choice::KeepAll,
        _ => {
            eprintln!("choice is identifiers, display or keep");
            std::process::exit(2);
        }
    };
    let data = std::fs::read(input).expect("read input");
    match strip(&data, mime, choice) {
        Ok(s) => s
            .write_to(std::fs::File::create(output).expect("create output"))
            .expect("write output"),
        Err(e) => {
            eprintln!("{input}: {e}");
            std::process::exit(1);
        }
    }
}
