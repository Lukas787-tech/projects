//! Native structured-output modes accept only a subset of JSON Schema.
//! Kickoff always validates replies against the full schema client-side, so
//! for the native request we strip constraints providers reject and close
//! every object with `additionalProperties: false`.

use serde_json::Value;

/// Keywords dropped from schemas sent to native JSON-schema modes.
const UNSUPPORTED: &[&str] = &[
    "minimum",
    "maximum",
    "exclusiveMinimum",
    "exclusiveMaximum",
    "multipleOf",
    "minLength",
    "maxLength",
    "pattern",
    "minItems",
    "maxItems",
    "uniqueItems",
    "minProperties",
    "maxProperties",
    "$schema",
    "$id",
];

pub fn for_native_mode(schema: &Value) -> Value {
    let mut out = schema.clone();
    sanitize(&mut out);
    out
}

fn sanitize(value: &mut Value) {
    match value {
        Value::Object(map) => {
            for key in UNSUPPORTED {
                map.remove(*key);
            }
            let is_object_schema = map
                .get("type")
                .is_some_and(|t| t == "object" || t.as_array().is_some_and(|a| a.iter().any(|x| x == "object")))
                || map.contains_key("properties");
            if is_object_schema {
                map.insert("additionalProperties".into(), Value::Bool(false));
            }
            for (key, child) in map.iter_mut() {
                // Property *names* under `properties` are user data, not keywords.
                if key == "properties" || key == "$defs" || key == "definitions" {
                    if let Value::Object(props) = child {
                        props.values_mut().for_each(sanitize);
                    }
                } else if key != "enum" && key != "const" {
                    sanitize(child);
                }
            }
        }
        Value::Array(items) => items.iter_mut().for_each(sanitize),
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    #[test]
    fn strips_constraints_and_closes_objects() {
        let schema = json!({
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "properties": {
                "minLength": {"type": "string", "minLength": 3},
                "pillars": {"type": "array", "minItems": 3, "maxItems": 5,
                            "items": {"type": "object", "properties": {"name": {"type": "string"}}}},
                "mood": {"enum": ["cozy", "tense"]}
            },
            "required": ["pillars"],
            "additionalProperties": true
        });
        let out = for_native_mode(&schema);
        assert_eq!(
            out,
            json!({
                "type": "object",
                "properties": {
                    "minLength": {"type": "string"},
                    "pillars": {"type": "array",
                                "items": {"type": "object", "properties": {"name": {"type": "string"}}, "additionalProperties": false}},
                    "mood": {"enum": ["cozy", "tense"]}
                },
                "required": ["pillars"],
                "additionalProperties": false
            })
        );
    }
}
