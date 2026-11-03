//! XMP: removes properties (attributes and elements) the choice does not
//! keep, at any depth, and copies everything else byte for byte. A start tag
//! is rebuilt only when one of its attributes goes, from the original bytes
//! of the attributes that stay. So filtering is idempotent: a second pass
//! finds nothing to remove and returns its input.
//!
//! The tokenizer covers what XMP packets use: elements, attributes, text,
//! CDATA, comments and processing instructions. A DOCTYPE is refused.

use std::ops::Range;

use crate::policy::{self, Strip, ns};

#[derive(Debug)]
struct Attr {
    /// The attribute with the whitespace before it.
    span: Range<usize>,
    name: Range<usize>,
    value: Range<usize>,
}

#[derive(Debug)]
enum Tok {
    Start {
        span: Range<usize>,
        name: Range<usize>,
        attrs: Vec<Attr>,
        tail: Range<usize>,
        empty: bool,
    },
    End {
        span: Range<usize>,
    },
    Text(Range<usize>),
    Other(Range<usize>),
}

fn is_ws(b: u8) -> bool {
    matches!(b, b' ' | b'\t' | b'\r' | b'\n')
}

fn find(d: &[u8], from: usize, pat: &[u8]) -> Option<usize> {
    d.get(from..)?
        .windows(pat.len())
        .position(|w| w == pat)
        .map(|p| from + p)
}

/// Tokens of a well-formed packet: every end tag closes the start tag it
/// names.
fn tokenize(d: &[u8]) -> Result<Vec<Tok>, String> {
    let mut toks = Vec::new();
    let mut open: Vec<Range<usize>> = Vec::new();
    let mut i = 0;
    while i < d.len() {
        if d[i] != b'<' {
            let end = d[i..]
                .iter()
                .position(|&b| b == b'<')
                .map_or(d.len(), |p| i + p);
            toks.push(Tok::Text(i..end));
            i = end;
            continue;
        }
        let rest = &d[i..];
        let (close, other) = if rest.starts_with(b"<?") {
            (b"?>".as_slice(), true)
        } else if rest.starts_with(b"<!--") {
            (b"-->".as_slice(), true)
        } else if rest.starts_with(b"<![CDATA[") {
            (b"]]>".as_slice(), false)
        } else if rest.starts_with(b"<!") {
            return Err("DOCTYPE in XMP".into());
        } else if rest.starts_with(b"</") {
            let end = find(d, i, b">").ok_or("unterminated end tag")? + 1;
            let name = d[i + 2..end - 1].trim_ascii_end();
            match open.pop() {
                Some(n) if d[n.clone()] == *name => {}
                _ => return Err("mismatched end tag".into()),
            }
            toks.push(Tok::End { span: i..end });
            i = end;
            continue;
        } else {
            let (tok, end) = start_tag(d, i)?;
            if let Tok::Start {
                name, empty: false, ..
            } = &tok
            {
                open.push(name.clone());
            }
            toks.push(tok);
            i = end;
            continue;
        };
        let end = find(d, i + 2, close).ok_or("unterminated markup")? + close.len();
        toks.push(if other {
            Tok::Other(i..end)
        } else {
            Tok::Text(i..end)
        });
        i = end;
    }
    if !open.is_empty() {
        return Err("unclosed element".into());
    }
    Ok(toks)
}

fn start_tag(d: &[u8], at: usize) -> Result<(Tok, usize), String> {
    let mut i = at + 1;
    let name_start = i;
    while i < d.len() && !is_ws(d[i]) && d[i] != b'/' && d[i] != b'>' {
        i += 1;
    }
    let name = name_start..i;
    if name.is_empty() {
        return Err("empty element name".into());
    }
    let mut attrs = Vec::new();
    loop {
        let ws = i;
        while i < d.len() && is_ws(d[i]) {
            i += 1;
        }
        match d.get(i) {
            None => return Err("unterminated start tag".into()),
            Some(b'>') => {
                let tok = Tok::Start {
                    span: at..i + 1,
                    name,
                    attrs,
                    tail: ws..i,
                    empty: false,
                };
                return Ok((tok, i + 1));
            }
            Some(b'/') if d.get(i + 1) == Some(&b'>') => {
                let tok = Tok::Start {
                    span: at..i + 2,
                    name,
                    attrs,
                    tail: ws..i,
                    empty: true,
                };
                return Ok((tok, i + 2));
            }
            Some(_) if ws == i => return Err("attribute without whitespace".into()),
            Some(_) => {}
        }
        let an = i;
        while i < d.len() && !is_ws(d[i]) && d[i] != b'=' && d[i] != b'>' && d[i] != b'/' {
            i += 1;
        }
        let aname = an..i;
        while i < d.len() && is_ws(d[i]) {
            i += 1;
        }
        if d.get(i) != Some(&b'=') || aname.is_empty() {
            return Err("attribute without a value".into());
        }
        i += 1;
        while i < d.len() && is_ws(d[i]) {
            i += 1;
        }
        let quote = *d.get(i).ok_or("unterminated attribute")?;
        if quote != b'"' && quote != b'\'' {
            return Err("unquoted attribute".into());
        }
        let vstart = i + 1;
        let vend = d[vstart..]
            .iter()
            .position(|&b| b == quote)
            .ok_or("unterminated attribute")?
            + vstart;
        i = vend + 1;
        attrs.push(Attr {
            span: ws..i,
            name: aname,
            value: vstart..vend,
        });
    }
}

/// Namespace declarations in scope.
struct Scopes<'a> {
    stack: Vec<Vec<(&'a [u8], String)>>,
}

impl<'a> Scopes<'a> {
    fn new() -> Self {
        Scopes { stack: Vec::new() }
    }

    fn push(&mut self, d: &'a [u8], attrs: &[Attr]) {
        let mut decls = Vec::new();
        for a in attrs {
            let name = &d[a.name.clone()];
            let prefix: &[u8] = if name == b"xmlns" {
                b""
            } else if let Some(p) = name.strip_prefix(b"xmlns:") {
                p
            } else {
                continue;
            };
            decls.push((prefix, unescape(&d[a.value.clone()])));
        }
        self.stack.push(decls);
    }

    fn pop(&mut self) {
        self.stack.pop();
    }

    fn uri(&self, prefix: &[u8]) -> Option<&str> {
        if prefix == b"xml" {
            return Some(ns::XML);
        }
        self.stack
            .iter()
            .rev()
            .flat_map(|s| s.iter().rev())
            .find(|(p, _)| *p == prefix)
            .map(|(_, u)| u.as_str())
    }

    /// (namespace, local name); attributes without a prefix have no namespace.
    fn resolve(&self, qname: &'a [u8], element: bool) -> (String, String) {
        let (prefix, local) = match qname.iter().position(|&b| b == b':') {
            Some(p) => (&qname[..p], &qname[p + 1..]),
            None if element => (&b""[..], qname),
            None => return (String::new(), String::from_utf8_lossy(qname).into_owned()),
        };
        let uri = self.uri(prefix).unwrap_or("").to_string();
        (uri, String::from_utf8_lossy(local).into_owned())
    }
}

fn unescape(v: &[u8]) -> String {
    let s = String::from_utf8_lossy(v);
    if !s.contains('&') {
        return s.into_owned();
    }
    let mut out = String::new();
    let mut rest = &s[..];
    while let Some(p) = rest.find('&') {
        out.push_str(&rest[..p]);
        rest = &rest[p..];
        let Some(semi) = rest.find(';') else { break };
        let ent = &rest[1..semi];
        let ch = match ent {
            "amp" => Some('&'),
            "lt" => Some('<'),
            "gt" => Some('>'),
            "quot" => Some('"'),
            "apos" => Some('\''),
            _ if ent.starts_with("#x") => u32::from_str_radix(&ent[2..], 16)
                .ok()
                .and_then(char::from_u32),
            _ if ent.starts_with('#') => ent[1..].parse().ok().and_then(char::from_u32),
            _ => None,
        };
        match ch {
            Some(c) => {
                out.push(c);
                rest = &rest[semi + 1..];
            }
            None => {
                out.push('&');
                rest = &rest[1..];
            }
        }
    }
    out.push_str(rest);
    out
}

/// RDF syntax and the x:xmpmeta wrapper: structure, not properties.
fn is_syntax(uri: &str) -> bool {
    uri == ns::RDF || uri == ns::X || uri == ns::XML
}

/// What to do with `xmpNote:HasExtendedXMP`.
#[derive(Clone, Copy, Debug)]
pub(crate) enum Extended<'g> {
    /// Leave it as it is.
    Keep,
    /// Point it at a new extended packet.
    Set(&'g str),
    /// The extended packet is gone.
    Remove,
}

pub(crate) struct Filtered {
    pub xml: Vec<u8>,
    /// Properties left.
    pub kept: usize,
    /// Properties removed.
    pub removed: usize,
}

pub(crate) fn filter(d: &[u8], strip: Strip, ext: Extended) -> Result<Filtered, String> {
    let toks = tokenize(d)?;
    let mut out = Vec::with_capacity(d.len());
    let mut scopes = Scopes::new();
    let (mut kept, mut removed) = (0, 0);
    // Whitespace before an element is held back, so a removed element does
    // not leave an empty line.
    let mut pending: Option<Range<usize>> = None;
    let mut skip = 0usize;
    let mut new_text: Option<&str> = None;
    for tok in &toks {
        if skip > 0 {
            match tok {
                Tok::Start { empty: false, .. } => skip += 1,
                Tok::End { .. } => skip -= 1,
                _ => {}
            }
            continue;
        }
        if let Tok::Text(r) = tok
            && d[r.clone()].iter().all(|&b| is_ws(b))
        {
            if let Some(p) = pending.replace(r.clone()) {
                out.extend_from_slice(&d[p]);
            }
            continue;
        }
        match tok {
            Tok::Start {
                span,
                name,
                attrs,
                tail,
                empty,
            } => {
                scopes.push(d, attrs);
                let (uri, local) = scopes.resolve(&d[name.clone()], true);
                let is_ext = uri == ns::XMP_NOTE && local == "HasExtendedXMP";
                let keep = if is_syntax(&uri) {
                    true
                } else if is_ext {
                    !matches!(ext, Extended::Remove)
                } else {
                    strip.keeps(policy::xmp_property(&uri, &local))
                };
                if !keep {
                    removed += 1;
                    pending = None;
                    scopes.pop();
                    if !empty {
                        skip = 1;
                    }
                    continue;
                }
                if !is_syntax(&uri) {
                    kept += 1;
                }
                if is_ext && let Extended::Set(g) = ext {
                    new_text = Some(g);
                }
                let mut parts: Vec<Vec<u8>> = Vec::new();
                let mut changed = false;
                for a in attrs {
                    let aname = &d[a.name.clone()];
                    if aname == b"xmlns" || aname.starts_with(b"xmlns:") {
                        parts.push(d[a.span.clone()].to_vec());
                        continue;
                    }
                    let (auri, alocal) = scopes.resolve(aname, false);
                    if auri.is_empty() || is_syntax(&auri) {
                        parts.push(d[a.span.clone()].to_vec());
                        continue;
                    }
                    if auri == ns::XMP_NOTE && alocal == "HasExtendedXMP" {
                        match ext {
                            Extended::Keep => parts.push(d[a.span.clone()].to_vec()),
                            Extended::Set(g) => {
                                let mut v = d[a.span.start..a.value.start].to_vec();
                                v.extend_from_slice(g.as_bytes());
                                v.extend_from_slice(&d[a.value.end..a.span.end]);
                                changed |= v != d[a.span.clone()];
                                parts.push(v);
                            }
                            Extended::Remove => {
                                changed = true;
                                removed += 1;
                                continue;
                            }
                        }
                        kept += 1;
                        continue;
                    }
                    if strip.keeps(policy::xmp_property(&auri, &alocal)) {
                        kept += 1;
                        parts.push(d[a.span.clone()].to_vec());
                    } else {
                        removed += 1;
                        changed = true;
                    }
                }
                if let Some(p) = pending.take() {
                    out.extend_from_slice(&d[p]);
                }
                if changed {
                    out.push(b'<');
                    out.extend_from_slice(&d[name.clone()]);
                    for p in parts {
                        out.extend_from_slice(&p);
                    }
                    out.extend_from_slice(&d[tail.clone()]);
                    out.extend_from_slice(if *empty { b"/>" } else { b">" });
                } else {
                    out.extend_from_slice(&d[span.clone()]);
                }
                if *empty {
                    scopes.pop();
                    new_text = None;
                }
            }
            Tok::End { span } => {
                if let Some(p) = pending.take() {
                    out.extend_from_slice(&d[p]);
                }
                if let Some(g) = new_text.take() {
                    // <xmpNote:HasExtendedXMP></xmpNote:HasExtendedXMP>
                    out.extend_from_slice(g.as_bytes());
                }
                scopes.pop();
                out.extend_from_slice(&d[span.clone()]);
            }
            Tok::Text(r) | Tok::Other(r) => {
                if let Some(p) = pending.take() {
                    out.extend_from_slice(&d[p]);
                }
                match new_text.take() {
                    Some(g) => out.extend_from_slice(g.as_bytes()),
                    None => out.extend_from_slice(&d[r.clone()]),
                }
            }
        }
    }
    if let Some(p) = pending.take() {
        out.extend_from_slice(&d[p]);
    }
    Ok(Filtered {
        xml: out,
        kept,
        removed,
    })
}

/// A property value read from the packet.
#[derive(Debug)]
pub(crate) struct Fact {
    pub uri: String,
    pub local: String,
    pub value: String,
    /// The `Container:Item` it belongs to, if any.
    pub item: Option<usize>,
}

/// Every simple property value, in document order.
pub(crate) fn facts(d: &[u8]) -> Result<Vec<Fact>, String> {
    let toks = tokenize(d)?;
    let mut scopes = Scopes::new();
    let mut facts = Vec::new();
    struct Open {
        uri: String,
        local: String,
        item: Option<usize>,
        text: Option<String>,
        has_children: bool,
    }
    let mut stack: Vec<Open> = Vec::new();
    let mut items = 0;
    for tok in &toks {
        match tok {
            Tok::Start {
                name, attrs, empty, ..
            } => {
                scopes.push(d, attrs);
                if let Some(top) = stack.last_mut() {
                    top.has_children = true;
                }
                let (uri, local) = scopes.resolve(&d[name.clone()], true);
                let mut item = stack.last().and_then(|s| s.item);
                if uri == ns::G_CONTAINER && local == "Item" {
                    item = Some(items);
                    items += 1;
                }
                for a in attrs {
                    let aname = &d[a.name.clone()];
                    if aname == b"xmlns" || aname.starts_with(b"xmlns:") {
                        continue;
                    }
                    let (auri, alocal) = scopes.resolve(aname, false);
                    let value = unescape(&d[a.value.clone()]);
                    facts.push(Fact {
                        uri: auri,
                        local: alocal,
                        value,
                        item,
                    });
                }
                if *empty {
                    scopes.pop();
                } else {
                    stack.push(Open {
                        uri,
                        local,
                        item,
                        text: None,
                        has_children: false,
                    });
                }
            }
            Tok::Text(r) => {
                if let Some(top) = stack.last_mut() {
                    top.text
                        .get_or_insert_with(String::new)
                        .push_str(&unescape(&d[r.clone()]));
                }
            }
            Tok::End { .. } => {
                scopes.pop();
                if let Some(o) = stack.pop()
                    && !o.has_children
                    && !is_syntax(&o.uri)
                {
                    let value = o.text.unwrap_or_default().trim().to_string();
                    facts.push(Fact {
                        uri: o.uri,
                        local: o.local,
                        value,
                        item: o.item,
                    });
                }
            }
            Tok::Other(_) => {}
        }
    }
    Ok(facts)
}

#[cfg(test)]
mod tests {
    use super::*;

    const PACKET: &str = r#"<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:exif="http://ns.adobe.com/exif/1.0/"
        xmlns:tiff="http://ns.adobe.com/tiff/1.0/"
        xmlns:xmpNote="http://ns.adobe.com/xmp/note/"
        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
      exif:GPSLatitude="43,9.8N"
      tiff:Orientation="6"
      exif:DateTimeOriginal="2020-01-02T03:04:05"
      xmpNote:HasExtendedXMP="0123"
      GCamera:MotionPhoto='1'>
      <tiff:Make>Google</tiff:Make>
      <exif:Flash rdf:parseType="Resource">
        <exif:Fired>False</exif:Fired>
      </exif:Flash>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"#;

    #[test]
    fn middle_choice_keeps_dates_and_structure() {
        let f = filter(PACKET.as_bytes(), Strip::Identifiers, Extended::Keep).unwrap();
        let s = String::from_utf8(f.xml.clone()).unwrap();
        assert!(!s.contains("GPS"), "{s}");
        assert!(!s.contains("Make"), "{s}");
        assert!(s.contains(r#"tiff:Orientation="6""#));
        assert!(s.contains("exif:DateTimeOriginal"));
        assert!(s.contains("<exif:Fired>False</exif:Fired>"));
        assert!(s.contains("GCamera:MotionPhoto='1'"));
        assert_eq!(f.removed, 2);
        let again = filter(&f.xml, Strip::Identifiers, Extended::Keep).unwrap();
        assert_eq!(again.removed, 0);
        assert_eq!(again.xml, f.xml);
    }

    #[test]
    fn strictest_choice_drops_dates_and_sets_guid() {
        let f = filter(
            PACKET.as_bytes(),
            Strip::AllButDisplay,
            Extended::Set("ABCD"),
        )
        .unwrap();
        let s = String::from_utf8(f.xml).unwrap();
        assert!(!s.contains("DateTimeOriginal"));
        assert!(!s.contains("Flash"));
        assert!(s.contains(r#"xmpNote:HasExtendedXMP="ABCD""#));
        assert!(!s.contains("\n\n"), "{s}");
    }

    #[test]
    fn facts_by_item() {
        let x = r#"<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
 xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"><GCamera:MotionPhoto>1</GCamera:MotionPhoto><Container:Directory><rdf:Seq>
<rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="Primary"/></rdf:li>
<rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="MotionPhoto" Item:Length="1234"/></rdf:li>
</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"#;
        let f = facts(x.as_bytes()).unwrap();
        assert!(f.iter().any(|f| f.local == "MotionPhoto" && f.value == "1"));
        let len = f.iter().find(|f| f.local == "Length").unwrap();
        assert_eq!((len.value.as_str(), len.item), ("1234", Some(1)));
    }
}
