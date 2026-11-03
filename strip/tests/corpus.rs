//! The private corpus: real files from the phones, in `private-fixtures/`
//! (git-ignored). Skipped when the folder is absent or empty, as in CI.

mod common;

use std::path::{Path, PathBuf};

use common::{CHOICES, check, mime_for};
use graphite_strip::{Choice, applies_to, strip};

fn files(dir: &Path, out: &mut Vec<PathBuf>) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    let mut entries: Vec<_> = entries.flatten().map(|e| e.path()).collect();
    entries.sort();
    for p in entries {
        if p.is_dir() {
            files(&p, out);
        } else if applies_to(mime_for(&p)) {
            out.push(p);
        }
    }
}

#[test]
fn private_corpus() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("private-fixtures");
    let mut all = Vec::new();
    files(&root, &mut all);
    if all.is_empty() {
        eprintln!("private-fixtures/ is empty; skipping the corpus");
        return;
    }
    let mut problems = Vec::new();
    for path in &all {
        let label = path.strip_prefix(&root).unwrap().display().to_string();
        let input = std::fs::read(path).unwrap();
        let mime = mime_for(path);
        assert!(strip(&input, mime, Choice::KeepAll).unwrap().is_unchanged());
        for choice in CHOICES {
            if let Some(out) = check(&label, &input, mime, choice, &mut problems) {
                eprintln!("{label} {choice:?}: {} -> {} bytes", input.len(), out.len());
            }
        }
    }
    assert!(
        problems.is_empty(),
        "{} problems:\n{}",
        problems.len(),
        problems.join("\n")
    );
}
