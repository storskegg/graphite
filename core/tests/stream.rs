mod common;

use common::pair;
use graphite_core::{CHUNK_OVERHEAD, CHUNK_SIZE, Error, Method};

#[test]
fn chunks_arrive_in_order_with_the_last_flag() {
    let (mut source, mut target) = pair(Method::Nfc);
    let big = vec![7u8; CHUNK_SIZE];
    let sealed = [
        source.seal(&big, false).unwrap(),
        source.seal(b"", false).unwrap(),
        source.seal(b"end", true).unwrap(),
    ];
    assert_eq!(sealed[0].len(), CHUNK_SIZE + CHUNK_OVERHEAD);

    let first = target.open(&sealed[0]).unwrap();
    assert_eq!(first.data, big);
    assert!(!first.last);
    assert_eq!(target.finish_receiving(), Err(Error::StreamTruncated));
    assert!(target.open(&sealed[1]).unwrap().data.is_empty());
    let last = target.open(&sealed[2]).unwrap();
    assert!(last.last);
    assert_eq!(last.data, b"end");
    assert_eq!(target.finish_receiving(), Ok(()));
}

#[test]
fn chunk_larger_than_one_mib_is_refused() {
    let (mut source, mut target) = pair(Method::Nfc);
    assert_eq!(
        source.seal(&vec![0u8; CHUNK_SIZE + 1], false),
        Err(Error::ChunkTooLarge)
    );
    assert_eq!(
        target.open(&vec![0u8; CHUNK_SIZE + CHUNK_OVERHEAD + 1]),
        Err(Error::ChunkTooLarge)
    );
}

#[test]
fn nothing_is_sealed_after_the_last_chunk() {
    let (mut source, _) = pair(Method::Nfc);
    source.seal(b"done", true).unwrap();
    assert_eq!(source.seal(b"more", false), Err(Error::StreamFinished));
}

#[test]
fn truncation_is_detected() {
    // The last chunk never arrives.
    let (mut source, mut target) = pair(Method::Nfc);
    let a = source.seal(b"one", false).unwrap();
    let _lost = source.seal(b"two", true).unwrap();
    target.open(&a).unwrap();
    assert_eq!(target.finish_receiving(), Err(Error::StreamTruncated));

    // A chunk is cut short.
    let (mut source, mut target) = pair(Method::Nfc);
    let a = source.seal(b"one two three", true).unwrap();
    assert_eq!(
        target.open(&a[..a.len() - 1]),
        Err(Error::StreamAuthentication)
    );

    // A chunk shorter than its header and tag.
    let (_, mut target) = pair(Method::Nfc);
    assert_eq!(
        target.open(&[0u8; CHUNK_OVERHEAD - 1]),
        Err(Error::Malformed)
    );
}

#[test]
fn clearing_the_last_flag_is_detected() {
    // An attacker cuts the stream after the last chunk and clears its flag,
    // hoping the receiver waits for more instead of noticing.
    let (mut source, mut target) = pair(Method::Nfc);
    let mut last = source.seal(b"end", true).unwrap();
    last[0] = 0;
    assert_eq!(target.open(&last), Err(Error::StreamAuthentication));

    // And setting it on a middle chunk, to end the stream early.
    let (mut source, mut target) = pair(Method::Nfc);
    let mut middle = source.seal(b"middle", false).unwrap();
    middle[0] = 1;
    assert_eq!(target.open(&middle), Err(Error::StreamAuthentication));

    // A flag byte that is neither 0 nor 1.
    let (mut source, mut target) = pair(Method::Nfc);
    let mut chunk = source.seal(b"x", false).unwrap();
    chunk[0] = 2;
    assert_eq!(target.open(&chunk), Err(Error::Malformed));
}

#[test]
fn reordering_is_detected() {
    let (mut source, mut target) = pair(Method::Nfc);
    let _a = source.seal(b"first", false).unwrap();
    let b = source.seal(b"second", false).unwrap();
    assert_eq!(target.open(&b), Err(Error::StreamAuthentication));
}

#[test]
fn replay_is_detected() {
    let (mut source, mut target) = pair(Method::Nfc);
    let a = source.seal(b"first", false).unwrap();
    target.open(&a).unwrap();
    assert_eq!(target.open(&a), Err(Error::StreamAuthentication));
}

#[test]
fn cross_direction_replay_is_detected() {
    // A chunk the source sent is bounced back to the source.
    let (mut source, mut target) = pair(Method::Nfc);
    let a = source.seal(b"to target", false).unwrap();
    assert_eq!(source.open(&a), Err(Error::StreamAuthentication));
    // And the target's own chunk back to the target.
    let b = target.seal(b"to source", false).unwrap();
    assert_eq!(target.open(&b), Err(Error::StreamAuthentication));
}

#[test]
fn replay_from_another_session_is_detected() {
    let (mut old_source, _) = pair(Method::Nfc);
    let (_, mut target) = pair(Method::Nfc);
    let a = old_source.seal(b"from yesterday", false).unwrap();
    assert_eq!(target.open(&a), Err(Error::StreamAuthentication));
}

#[test]
fn tampered_ciphertext_is_detected() {
    let (mut source, mut target) = pair(Method::Nfc);
    let mut a = source.seal(b"secret photo bytes", false).unwrap();
    a[3] ^= 0x80;
    assert_eq!(target.open(&a), Err(Error::StreamAuthentication));
}

#[test]
fn a_failed_stream_stays_failed() {
    let (mut source, mut target) = pair(Method::Nfc);
    let a = source.seal(b"first", false).unwrap();
    let mut bad = a.clone();
    bad[5] ^= 1;
    assert_eq!(target.open(&bad), Err(Error::StreamAuthentication));
    // The genuine chunk is refused too: after any error the stream is dead.
    assert_eq!(target.open(&a), Err(Error::Failed));
    assert_eq!(target.finish_receiving(), Err(Error::Failed));
}

#[test]
fn data_after_the_last_chunk_is_rejected() {
    let (mut source, mut target) = pair(Method::Nfc);
    let a = source.seal(b"end", true).unwrap();
    target.open(&a).unwrap();
    assert_eq!(target.open(&a), Err(Error::StreamFinished));
}
