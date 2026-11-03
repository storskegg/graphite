use std::io::{self, Write};
use std::ops::Range;

/// A stripped file: ranges of the input interleaved with new bytes.
#[derive(Clone, Debug)]
pub struct Stripped<'a> {
    input: &'a [u8],
    pieces: Vec<Piece>,
    len: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum Piece {
    Input(Range<usize>),
    Bytes(Vec<u8>),
}

/// One piece of a [`Stripped`] file, in order.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Part<'a> {
    /// Bytes copied from the input, at this offset in the input.
    Input { offset: usize, bytes: &'a [u8] },
    /// Bytes the stripper wrote.
    New(&'a [u8]),
}

impl<'a> Part<'a> {
    pub fn bytes(&self) -> &'a [u8] {
        match *self {
            Part::Input { bytes, .. } | Part::New(bytes) => bytes,
        }
    }
}

impl<'a> Stripped<'a> {
    pub(crate) fn unchanged(input: &'a [u8]) -> Self {
        let mut out = Out::new(input);
        out.copy(0..input.len());
        out.finish()
    }

    /// Length of the output in bytes.
    pub fn len(&self) -> u64 {
        self.len
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// Whether the output is the input, byte for byte.
    pub fn is_unchanged(&self) -> bool {
        match self.pieces.as_slice() {
            [] => self.input.is_empty(),
            [Piece::Input(r)] => *r == (0..self.input.len()),
            _ => false,
        }
    }

    /// The pieces of the output, in order; their concatenation is the file.
    pub fn parts(&self) -> impl Iterator<Item = Part<'_>> {
        self.pieces.iter().map(|p| match p {
            Piece::Input(r) => Part::Input {
                offset: r.start,
                bytes: &self.input[r.clone()],
            },
            Piece::Bytes(b) => Part::New(b),
        })
    }

    pub fn write_to(&self, mut w: impl Write) -> io::Result<()> {
        for part in self.parts() {
            w.write_all(part.bytes())?;
        }
        Ok(())
    }

    pub fn to_vec(&self) -> Vec<u8> {
        let mut v = Vec::with_capacity(self.len as usize);
        for part in self.parts() {
            v.extend_from_slice(part.bytes());
        }
        v
    }
}

/// Builds a [`Stripped`] in order.
pub(crate) struct Out<'a> {
    input: &'a [u8],
    pieces: Vec<Piece>,
    len: usize,
}

impl<'a> Out<'a> {
    pub fn new(input: &'a [u8]) -> Self {
        Out {
            input,
            pieces: Vec::new(),
            len: 0,
        }
    }

    /// Current length of the output.
    pub fn len(&self) -> usize {
        self.len
    }

    pub fn copy(&mut self, r: Range<usize>) {
        debug_assert!(r.start <= r.end && r.end <= self.input.len());
        if r.is_empty() {
            return;
        }
        self.len += r.len();
        if let Some(Piece::Input(last)) = self.pieces.last_mut()
            && last.end == r.start
        {
            last.end = r.end;
            return;
        }
        self.pieces.push(Piece::Input(r));
    }

    pub fn bytes(&mut self, b: &[u8]) {
        if b.is_empty() {
            return;
        }
        self.len += b.len();
        if let Some(Piece::Bytes(last)) = self.pieces.last_mut() {
            last.extend_from_slice(b);
            return;
        }
        self.pieces.push(Piece::Bytes(b.to_vec()));
    }

    /// Writes new bytes as their own piece and returns its index, so they
    /// can be replaced once later offsets are known (same length).
    pub fn placeholder(&mut self, b: Vec<u8>) -> usize {
        self.len += b.len();
        self.pieces.push(Piece::Bytes(b));
        self.pieces.len() - 1
    }

    pub fn replace(&mut self, index: usize, b: Vec<u8>) {
        match &mut self.pieces[index] {
            Piece::Bytes(old) => {
                assert_eq!(old.len(), b.len());
                *old = b;
            }
            Piece::Input(_) => unreachable!("placeholders are byte pieces"),
        }
    }

    /// Copies `r` from the input with same-length patches applied.
    /// `patches` are (offset in the input, new bytes), sorted and disjoint.
    pub fn copy_patched(&mut self, r: Range<usize>, patches: &[(usize, Vec<u8>)]) {
        let mut at = r.start;
        for (off, bytes) in patches {
            debug_assert!(*off >= at && off + bytes.len() <= r.end);
            self.copy(at..*off);
            if self.input[*off..off + bytes.len()] == bytes[..] {
                self.copy(*off..off + bytes.len());
            } else {
                self.bytes(bytes);
            }
            at = off + bytes.len();
        }
        self.copy(at..r.end);
    }

    pub fn finish(self) -> Stripped<'a> {
        Stripped {
            input: self.input,
            pieces: self.pieces,
            len: self.len as u64,
        }
    }
}
