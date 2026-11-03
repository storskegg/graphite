//! A strict binary encoding: big-endian integers, u32 length prefixes,
//! no trailing bytes.

use crate::Error;

#[derive(Default)]
pub(crate) struct Writer(Vec<u8>);

impl Writer {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn u8(&mut self, v: u8) -> &mut Self {
        self.0.push(v);
        self
    }
    pub fn u16(&mut self, v: u16) -> &mut Self {
        self.fixed(&v.to_be_bytes())
    }
    pub fn u32(&mut self, v: u32) -> &mut Self {
        self.fixed(&v.to_be_bytes())
    }
    pub fn u64(&mut self, v: u64) -> &mut Self {
        self.fixed(&v.to_be_bytes())
    }
    pub fn i64(&mut self, v: i64) -> &mut Self {
        self.fixed(&v.to_be_bytes())
    }
    pub fn fixed(&mut self, v: &[u8]) -> &mut Self {
        self.0.extend_from_slice(v);
        self
    }
    pub fn bytes(&mut self, v: &[u8]) -> &mut Self {
        let len = u32::try_from(v.len()).expect("field over 4 GiB");
        self.u32(len).fixed(v)
    }
    pub fn str(&mut self, v: &str) -> &mut Self {
        self.bytes(v.as_bytes())
    }
    pub fn finish(&mut self) -> Vec<u8> {
        core::mem::take(&mut self.0)
    }
}

pub(crate) struct Reader<'a> {
    buf: &'a [u8],
}

impl<'a> Reader<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        Self { buf }
    }
    pub fn take(&mut self, n: usize) -> Result<&'a [u8], Error> {
        if self.buf.len() < n {
            return Err(Error::Malformed);
        }
        let (head, rest) = self.buf.split_at(n);
        self.buf = rest;
        Ok(head)
    }
    pub fn array<const N: usize>(&mut self) -> Result<[u8; N], Error> {
        Ok(self.take(N)?.try_into().expect("length checked"))
    }
    pub fn u8(&mut self) -> Result<u8, Error> {
        Ok(self.array::<1>()?[0])
    }
    pub fn u16(&mut self) -> Result<u16, Error> {
        Ok(u16::from_be_bytes(self.array()?))
    }
    pub fn u32(&mut self) -> Result<u32, Error> {
        Ok(u32::from_be_bytes(self.array()?))
    }
    pub fn u64(&mut self) -> Result<u64, Error> {
        Ok(u64::from_be_bytes(self.array()?))
    }
    pub fn i64(&mut self) -> Result<i64, Error> {
        Ok(i64::from_be_bytes(self.array()?))
    }
    pub fn bytes(&mut self) -> Result<&'a [u8], Error> {
        let len = self.u32()? as usize;
        self.take(len)
    }
    pub fn str(&mut self) -> Result<String, Error> {
        let bytes = self.bytes()?;
        String::from_utf8(bytes.to_vec()).map_err(|_| Error::Malformed)
    }
    /// Fails unless every byte was read.
    pub fn end(&self) -> Result<(), Error> {
        if self.buf.is_empty() {
            Ok(())
        } else {
            Err(Error::Malformed)
        }
    }
}
