//! Downloads for speech models, voices and the Piper engine. Files are
//! written next to their destination as `*.part`, checked against a known
//! checksum, and only then moved into place, so a broken download never
//! looks installed.

use std::path::{Path, PathBuf};

use futures::StreamExt;
use kickoff_core::voice::Cancel;
use md5::Md5;
use sha1::Sha1;
use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Checksum {
    Sha1(String),
    Sha256(String),
    Md5(String),
}

#[derive(Debug, thiserror::Error)]
pub enum DownloadError {
    #[error("couldn't download {url}: {message}")]
    Network { url: String, message: String },
    #[error("{url} answered HTTP {status}")]
    Status { url: String, status: u16 },
    #[error("the download was damaged (checksum mismatch); try again")]
    Checksum { expected: String, actual: String },
    #[error("the download was {actual} bytes, expected {expected}")]
    Size { expected: u64, actual: u64 },
    #[error("couldn't save the file: {0}")]
    Io(#[from] std::io::Error),
    #[error("download cancelled")]
    Cancelled,
}

/// One file to fetch.
#[derive(Debug, Clone)]
pub struct Download {
    pub url: String,
    pub dest: PathBuf,
    pub checksum: Option<Checksum>,
    pub size: Option<u64>,
}

enum Hasher {
    Sha1(Sha1),
    Sha256(Sha256),
    Md5(Md5),
    None,
}

impl Hasher {
    fn for_checksum(c: Option<&Checksum>) -> Self {
        match c {
            Some(Checksum::Sha1(_)) => Self::Sha1(Sha1::new()),
            Some(Checksum::Sha256(_)) => Self::Sha256(Sha256::new()),
            Some(Checksum::Md5(_)) => Self::Md5(Md5::new()),
            None => Self::None,
        }
    }

    fn update(&mut self, bytes: &[u8]) {
        match self {
            Self::Sha1(h) => h.update(bytes),
            Self::Sha256(h) => h.update(bytes),
            Self::Md5(h) => h.update(bytes),
            Self::None => {}
        }
    }

    fn finish(self) -> String {
        match self {
            Self::Sha1(h) => hex(&h.finalize()),
            Self::Sha256(h) => hex(&h.finalize()),
            Self::Md5(h) => hex(&h.finalize()),
            Self::None => String::new(),
        }
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn expected(c: &Checksum) -> &str {
    match c {
        Checksum::Sha1(s) | Checksum::Sha256(s) | Checksum::Md5(s) => s,
    }
}

/// Fetch `d`, reporting `(bytes so far, total if known)` as it goes.
pub async fn download(
    client: &reqwest::Client,
    d: &Download,
    on_progress: &mut (dyn FnMut(u64, Option<u64>) + Send),
    cancel: &Cancel,
) -> Result<(), DownloadError> {
    let network = |e: reqwest::Error| DownloadError::Network { url: d.url.clone(), message: e.to_string() };
    let response = client.get(&d.url).send().await.map_err(network)?;
    if !response.status().is_success() {
        return Err(DownloadError::Status { url: d.url.clone(), status: response.status().as_u16() });
    }
    let total = response.content_length().or(d.size);
    if let Some(dir) = d.dest.parent() {
        tokio::fs::create_dir_all(dir).await?;
    }
    let part = part_path(&d.dest);
    let mut file = tokio::fs::File::create(&part).await?;
    let mut hasher = Hasher::for_checksum(d.checksum.as_ref());
    let mut received = 0u64;
    let mut body = response.bytes_stream();
    let result = async {
        loop {
            let chunk = tokio::select! {
                biased;
                _ = cancel.cancelled() => return Err(DownloadError::Cancelled),
                c = body.next() => c,
            };
            let Some(chunk) = chunk else { break };
            let chunk = chunk.map_err(network)?;
            hasher.update(&chunk);
            file.write_all(&chunk).await?;
            received += chunk.len() as u64;
            on_progress(received, total);
        }
        file.flush().await?;
        if let Some(size) = d.size
            && size != received
        {
            return Err(DownloadError::Size { expected: size, actual: received });
        }
        if let Some(checksum) = &d.checksum {
            let actual = hasher.finish();
            if !actual.eq_ignore_ascii_case(expected(checksum)) {
                return Err(DownloadError::Checksum { expected: expected(checksum).to_owned(), actual });
            }
        }
        Ok(())
    }
    .await;
    drop(file);
    match result {
        Ok(()) => {
            tokio::fs::rename(&part, &d.dest).await?;
            Ok(())
        }
        Err(e) => {
            let _ = tokio::fs::remove_file(&part).await;
            Err(e)
        }
    }
}

fn part_path(dest: &Path) -> PathBuf {
    let mut name = dest.file_name().map(|n| n.to_os_string()).unwrap_or_default();
    name.push(".part");
    dest.with_file_name(name)
}

/// Check a file already on disk.
pub fn verify(path: &Path, checksum: &Checksum) -> std::io::Result<bool> {
    use std::io::Read;
    let mut file = std::fs::File::open(path)?;
    let mut hasher = Hasher::for_checksum(Some(checksum));
    let mut buf = vec![0u8; 1 << 16];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hasher.finish().eq_ignore_ascii_case(expected(checksum)))
}

#[cfg(test)]
mod tests {
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    use super::*;

    const BODY: &[u8] = b"hello model";

    async fn server() -> MockServer {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/model.bin"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(BODY))
            .mount(&server)
            .await;
        server
    }

    fn sha1_of(bytes: &[u8]) -> String {
        hex(&Sha1::digest(bytes))
    }

    #[tokio::test]
    async fn downloads_verifies_and_reports_progress() {
        let server = server().await;
        let dir = tempfile::tempdir().unwrap();
        let dest = dir.path().join("whisper/model.bin");
        let d = Download {
            url: format!("{}/model.bin", server.uri()),
            dest: dest.clone(),
            checksum: Some(Checksum::Sha1(sha1_of(BODY))),
            size: Some(BODY.len() as u64),
        };
        let mut last = (0, None);
        download(&reqwest::Client::new(), &d, &mut |n, t| last = (n, t), &Cancel::new()).await.unwrap();
        assert_eq!(std::fs::read(&dest).unwrap(), BODY);
        assert_eq!(last, (BODY.len() as u64, Some(BODY.len() as u64)));
        assert!(verify(&dest, &Checksum::Sha1(sha1_of(BODY))).unwrap());
        assert!(!part_path(&dest).exists());
    }

    #[tokio::test]
    async fn a_bad_checksum_leaves_nothing_behind() {
        let server = server().await;
        let dir = tempfile::tempdir().unwrap();
        let dest = dir.path().join("model.bin");
        let d = Download {
            url: format!("{}/model.bin", server.uri()),
            dest: dest.clone(),
            checksum: Some(Checksum::Sha1("0".repeat(40))),
            size: None,
        };
        let err = download(&reqwest::Client::new(), &d, &mut |_, _| {}, &Cancel::new()).await.unwrap_err();
        assert!(matches!(err, DownloadError::Checksum { .. }), "{err}");
        assert!(!dest.exists());
        assert!(!part_path(&dest).exists());
    }

    #[tokio::test]
    async fn http_errors_and_md5() {
        let server = server().await;
        let dir = tempfile::tempdir().unwrap();
        let missing = Download {
            url: format!("{}/nope.bin", server.uri()),
            dest: dir.path().join("x"),
            checksum: None,
            size: None,
        };
        let err = download(&reqwest::Client::new(), &missing, &mut |_, _| {}, &Cancel::new()).await.unwrap_err();
        assert!(matches!(err, DownloadError::Status { status: 404, .. }));

        let md5 = hex(&Md5::digest(BODY));
        let d = Download {
            url: format!("{}/model.bin", server.uri()),
            dest: dir.path().join("voice.onnx"),
            checksum: Some(Checksum::Md5(md5)),
            size: None,
        };
        download(&reqwest::Client::new(), &d, &mut |_, _| {}, &Cancel::new()).await.unwrap();
    }
}
