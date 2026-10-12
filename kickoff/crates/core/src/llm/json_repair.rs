//! Lenient JSON extraction for model output.
//!
//! Free and local models often wrap JSON in prose or code fences, emit
//! `<think>` blocks, or leave trailing commas. This recovers the JSON value
//! when the intent is unambiguous; schema validation happens afterwards.

use serde_json::Value;

/// Find and parse the JSON value in a model reply.
pub fn extract_json(reply: &str) -> Result<Value, String> {
    let text = strip_think_blocks(reply);

    let mut candidates = fenced_blocks(&text);
    candidates.push(text.clone());

    let mut last_error = String::from("no JSON object found in the reply");
    for candidate in candidates {
        let Some(span) = balanced_span(&candidate) else { continue };
        if let Ok(v) = serde_json::from_str::<Value>(span) {
            return Ok(v);
        }
        match serde_json::from_str::<Value>(&repair(span)) {
            Ok(v) => return Ok(v),
            Err(e) => last_error = e.to_string(),
        }
    }
    Err(last_error)
}

/// Remove `<think>…</think>` reasoning blocks (Qwen, DeepSeek and others).
fn strip_think_blocks(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(start) = rest.find("<think>") {
        out.push_str(&rest[..start]);
        match rest[start..].find("</think>") {
            Some(end) => rest = &rest[start + end + "</think>".len()..],
            None => {
                rest = "";
                break;
            }
        }
    }
    out.push_str(rest);
    out
}

/// Contents of ``` fenced blocks, in order.
fn fenced_blocks(text: &str) -> Vec<String> {
    let mut blocks = Vec::new();
    let mut rest = text;
    while let Some(open) = rest.find("```") {
        let after = &rest[open + 3..];
        // Skip an optional language tag on the opening line.
        let body_start = after.find('\n').map_or(0, |i| i + 1);
        let body = &after[body_start..];
        match body.find("```") {
            Some(close) => {
                blocks.push(body[..close].to_owned());
                rest = &body[close + 3..];
            }
            None => break,
        }
    }
    blocks
}

/// The first `{…}` or `[…]` span with balanced brackets, ignoring brackets
/// inside strings.
fn balanced_span(text: &str) -> Option<&str> {
    let start = text.find(['{', '['])?;
    let mut depth = 0usize;
    let mut in_string = false;
    let mut escaped = false;
    for (i, c) in text[start..].char_indices() {
        if in_string {
            match c {
                _ if escaped => escaped = false,
                '\\' => escaped = true,
                '"' => in_string = false,
                _ => {}
            }
            continue;
        }
        match c {
            '"' => in_string = true,
            '{' | '[' => depth += 1,
            '}' | ']' => {
                depth -= 1;
                if depth == 0 {
                    return Some(&text[start..start + i + c.len_utf8()]);
                }
            }
            _ => {}
        }
    }
    None
}

/// Small, safe syntax fixes outside string literals: drop trailing commas and
/// `//` line comments, and turn curly quotes into straight ones.
fn repair(json: &str) -> String {
    let normalized: String = json.chars().map(|c| if matches!(c, '\u{201C}' | '\u{201D}') { '"' } else { c }).collect();
    let chars: Vec<char> = normalized.chars().collect();
    let mut out = String::with_capacity(chars.len());
    let mut in_string = false;
    let mut escaped = false;
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        if in_string {
            out.push(c);
            match c {
                _ if escaped => escaped = false,
                '\\' => escaped = true,
                '"' => in_string = false,
                _ => {}
            }
            i += 1;
            continue;
        }
        match c {
            '"' => {
                in_string = true;
                out.push(c);
            }
            '/' if chars.get(i + 1) == Some(&'/') => {
                while i < chars.len() && chars[i] != '\n' {
                    i += 1;
                }
                continue;
            }
            ',' => {
                let next = chars[i + 1..].iter().find(|c| !c.is_whitespace());
                if !matches!(next, Some('}') | Some(']')) {
                    out.push(c);
                }
            }
            _ => out.push(c),
        }
        i += 1;
    }
    out
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    #[test]
    fn plain_json() {
        assert_eq!(extract_json(r#"{"a": 1}"#).unwrap(), json!({"a": 1}));
    }

    #[test]
    fn fenced_with_prose() {
        let reply = "Sure! Here it is:\n```json\n{\"title\": \"Hearth\", \"tags\": [\"cozy\"]}\n```\nAnything else?";
        assert_eq!(extract_json(reply).unwrap(), json!({"title": "Hearth", "tags": ["cozy"]}));
    }

    #[test]
    fn think_block_and_trailing_commas() {
        let reply = "<think>The user wants {json}...</think>\n{\"a\": [1, 2,], \"b\": {\"c\": \"x\",},}";
        assert_eq!(extract_json(reply).unwrap(), json!({"a": [1, 2], "b": {"c": "x"}}));
    }

    #[test]
    fn brackets_and_commas_inside_strings_are_kept() {
        let reply = r#"{"pitch": "Build, explore, survive {cozy}", "n": 2}"#;
        assert_eq!(extract_json(reply).unwrap()["pitch"], "Build, explore, survive {cozy}");
    }

    #[test]
    fn line_comments_and_curly_quotes() {
        let reply = "{\u{201C}a\u{201D}: 1, // the answer\n \"b\": 2}";
        assert_eq!(extract_json(reply).unwrap(), json!({"a": 1, "b": 2}));
    }

    #[test]
    fn url_inside_string_is_not_a_comment() {
        let reply = r#"{"url": "https://polyhaven.com/a/rock"}"#;
        assert_eq!(extract_json(reply).unwrap()["url"], "https://polyhaven.com/a/rock");
    }

    #[test]
    fn truncated_output_fails() {
        assert!(extract_json(r#"{"a": [1, 2"#).is_err());
        assert!(extract_json("no json here").is_err());
    }
}
