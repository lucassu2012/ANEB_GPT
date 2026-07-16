"""Fail-closed validator for the small JSON Schema subset used by Profile v2.

This is test infrastructure, not a general Draft 2020-12 implementation. Any new
schema keyword must fail explicitly until a pinned full validator is approved.
"""

from __future__ import annotations

import math
from typing import Any


class ContractSchemaError(AssertionError):
    pass


_SUPPORTED_KEYWORDS = {
    "$schema",
    "$id",
    "$defs",
    "$ref",
    "title",
    "description",
    "type",
    "required",
    "properties",
    "additionalProperties",
    "const",
    "enum",
    "minLength",
    "minimum",
    "minItems",
    "items",
}


def validate(instance: Any, schema: dict[str, Any]) -> None:
    _check_json_value(instance, "$")
    _check_schema(schema, schema, "$")
    _validate(instance, schema, schema, "$")


def _check_json_value(value: Any, path: str) -> None:
    """Reject Python values that a strict JSON parser could never produce."""
    if value is None or isinstance(value, (bool, str, int)):
        return
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ContractSchemaError(f"{path}: non-finite numbers are not valid JSON")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            _check_json_value(item, f"{path}[{index}]")
        return
    if isinstance(value, dict):
        for name, item in value.items():
            if not isinstance(name, str):
                raise ContractSchemaError(f"{path}: JSON object key is not a string")
            _check_json_value(item, f"{path}.{name}")
        return
    raise ContractSchemaError(f"{path}: {type(value).__name__} is not a JSON value")


def _check_schema(
    schema: Any,
    root: dict[str, Any],
    path: str,
) -> None:
    """Preflight every subschema, including branches the instance does not visit."""
    if not isinstance(schema, dict):
        raise ContractSchemaError(f"{path}: schema must be an object")

    unsupported = sorted(set(schema) - _SUPPORTED_KEYWORDS)
    if unsupported:
        raise ContractSchemaError(f"{path}: unsupported schema keywords {unsupported}")

    expected = schema.get("type")
    if expected is not None:
        names = [expected] if isinstance(expected, str) else expected
        if (
            not isinstance(names, list)
            or not names
            or not all(isinstance(name, str) for name in names)
        ):
            raise ContractSchemaError(f"{path}.type: expected a type name or non-empty list")
        supported_types = {"null", "boolean", "integer", "number", "string", "array", "object"}
        unknown_types = sorted(set(names) - supported_types)
        if unknown_types:
            raise ContractSchemaError(f"{path}.type: unsupported JSON Schema types {unknown_types}")

    reference = schema.get("$ref")
    if reference is not None:
        if not isinstance(reference, str):
            raise ContractSchemaError(f"{path}.$ref: reference must be a string")
        _resolve(reference, root)

    for container_name in ("$defs", "properties"):
        container = schema.get(container_name, {})
        if not isinstance(container, dict):
            raise ContractSchemaError(f"{path}.{container_name}: expected an object")
        for name, child in container.items():
            _check_schema(child, root, f"{path}.{container_name}.{name}")

    additional = schema.get("additionalProperties", True)
    if not isinstance(additional, bool):
        _check_schema(additional, root, f"{path}.additionalProperties")

    if "items" in schema:
        _check_schema(schema["items"], root, f"{path}.items")


def _validate(
    instance: Any,
    schema: dict[str, Any],
    root: dict[str, Any],
    path: str,
) -> None:
    unsupported = sorted(set(schema) - _SUPPORTED_KEYWORDS)
    if unsupported:
        raise ContractSchemaError(f"{path}: unsupported schema keywords {unsupported}")

    reference = schema.get("$ref")
    if reference is not None:
        _validate(instance, _resolve(reference, root), root, path)

    if "type" in schema and not _matches_type(instance, schema["type"]):
        raise ContractSchemaError(
            f"{path}: expected type {schema['type']!r}, got {type(instance).__name__}"
        )
    if "const" in schema and not _json_equal(instance, schema["const"]):
        raise ContractSchemaError(f"{path}: expected const {schema['const']!r}")
    if "enum" in schema and not any(_json_equal(instance, item) for item in schema["enum"]):
        raise ContractSchemaError(f"{path}: value {instance!r} is not in enum")

    if isinstance(instance, str):
        minimum = schema.get("minLength")
        if minimum is not None and len(instance) < minimum:
            raise ContractSchemaError(f"{path}: string is shorter than {minimum}")

    if _is_number(instance):
        minimum = schema.get("minimum")
        if minimum is not None and instance < minimum:
            raise ContractSchemaError(f"{path}: number is below minimum {minimum}")

    if isinstance(instance, list):
        minimum = schema.get("minItems")
        if minimum is not None and len(instance) < minimum:
            raise ContractSchemaError(f"{path}: array has fewer than {minimum} items")
        item_schema = schema.get("items")
        if item_schema is not None:
            for index, item in enumerate(instance):
                _validate(item, item_schema, root, f"{path}[{index}]")

    if isinstance(instance, dict):
        for name in schema.get("required", []):
            if name not in instance:
                raise ContractSchemaError(f"{path}: missing required property {name!r}")
        properties = schema.get("properties", {})
        for name, value in instance.items():
            if name in properties:
                _validate(value, properties[name], root, f"{path}.{name}")
                continue
            additional = schema.get("additionalProperties", True)
            if additional is False:
                raise ContractSchemaError(f"{path}: unexpected property {name!r}")
            if isinstance(additional, dict):
                _validate(value, additional, root, f"{path}.{name}")


def _resolve(reference: str, root: dict[str, Any]) -> dict[str, Any]:
    if not reference.startswith("#/"):
        raise ContractSchemaError(f"external schema reference is not supported: {reference}")
    current: Any = root
    for encoded in reference[2:].split("/"):
        token = encoded.replace("~1", "/").replace("~0", "~")
        if not isinstance(current, dict) or token not in current:
            raise ContractSchemaError(f"unresolvable schema reference: {reference}")
        current = current[token]
    if not isinstance(current, dict):
        raise ContractSchemaError(f"schema reference is not an object: {reference}")
    return current


def _matches_type(instance: Any, expected: str | list[str]) -> bool:
    names = [expected] if isinstance(expected, str) else expected
    return any(_matches_single_type(instance, name) for name in names)


def _matches_single_type(instance: Any, expected: str) -> bool:
    if expected == "null":
        return instance is None
    if expected == "boolean":
        return isinstance(instance, bool)
    if expected == "integer":
        return isinstance(instance, int) and not isinstance(instance, bool)
    if expected == "number":
        return _is_number(instance)
    if expected == "string":
        return isinstance(instance, str)
    if expected == "array":
        return isinstance(instance, list)
    if expected == "object":
        return isinstance(instance, dict)
    raise ContractSchemaError(f"unsupported JSON Schema type: {expected}")


def _is_number(instance: Any) -> bool:
    if isinstance(instance, bool) or not isinstance(instance, (int, float)):
        return False
    return not isinstance(instance, float) or math.isfinite(instance)


def _json_equal(left: Any, right: Any) -> bool:
    """JSON-aware equality: booleans are not the numbers 0 and 1."""
    if _is_number(left) and _is_number(right):
        return left == right
    if type(left) is not type(right):
        return False
    if isinstance(left, list):
        return len(left) == len(right) and all(
            _json_equal(left_item, right_item)
            for left_item, right_item in zip(left, right)
        )
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(
            _json_equal(left[name], right[name]) for name in left
        )
    return left == right
