"""
  Jupyter notebook runtime variable extraction using debugpy's standard DAP infrastructure.
  No dependency on _pydevd_bundle.custom package.
  """
import sys
import json
import inspect

from debugpy._vendored.pydevd._pydevd_bundle.pydevd_resolver import (
    get_var_scope,
    sorted_attributes_key,
    TOO_LARGE_ATTR,
    MoreItemsRange,
    MoreItems
)
from debugpy._vendored.pydevd._pydevd_bundle.pydevd_vars import (
    eval_in_context,
    resolve_var_object,
    resolve_compound_var_object_fields,
    table_like_struct_to_xml,
)
from debugpy._vendored.pydevd._pydevd_bundle.pydevd_xml import (
    get_type,
    get_variable_details,
    should_evaluate_full_value,
    ExceptionOnEvaluate,
)
from debugpy._vendored.pydevd._pydevd_bundle.pydevd_utils import is_string
from debugpy._vendored.pydevd._pydevd_bundle.pydevd_safe_repr import SafeRepr
from debugpy._vendored.pydevd._pydevd_bundle.pydevd_utils import (
    DAPGrouper,
)
from debugpy._vendored.pydevd._pydevd_bundle.pydevd_constants import (
    NEXT_VALUE_SEPARATOR,
    GENERATED_LEN_ATTR_NAME,
)

from pycharm_tables.pydevd_tables import exec_image_table_command

_GROUP_EXPRESSION_PREFIX = "__jupyter_debug_group__:"
_HIDDEN_TYPES = ("function", "type", "classobj", "module", "typing")


def get_frame():
    """Dump all user namespace variables as DAP Variable dicts."""
    try:
        ipython_shell = get_ipython()
        user_ns = ipython_shell.user_ns
        hidden_ns = getattr(ipython_shell, "user_ns_hidden", {})
        hidden_names = set(hidden_ns)

        variables = _build_dap_variables(
                  _iter_namespace_entries(user_ns),
                  hidden_names=hidden_names,
                  parent_evaluate_name=None,
              )

        print(json.dumps({"variables": variables}))
    except Exception:
        print("")
    finally:
        sys.stdout.flush()


def get_variable(pydev_text):
    """Resolve compound variable children as DAP Variable dicts."""
    ipython_shell = get_ipython()
    # todo: types_renderers

    # Handle DAPGrouper scope expansion
    decoded = _decode_group_expression(pydev_text)
    if decoded is not None:
        parent_evaluate_name, scope = decoded
        hidden_names = None
        if parent_evaluate_name is None:
            # Top-level namespace: need hidden_names to classify vars correctly
            hidden_ns = getattr(ipython_shell, "user_ns_hidden", {})
            hidden_names = set(hidden_ns)

        entries = _resolve_group_entries(
            ipython_shell.user_ns, parent_evaluate_name, scope, hidden_names=hidden_names
        )
        variables = []
        for name, value, evaluate_name in entries:
            eval_full = should_evaluate_full_value(value)
            var_data = _var_to_dap_dict(value, name, evaluate_full_value=eval_full)
            if evaluate_name is not None:
                var_data["evaluateName"] = evaluate_name
            variables.append(var_data)
        print(json.dumps({"variables": variables}))
        return

    variables = _build_compound_dap_variables(ipython_shell.user_ns, pydev_text)
    print(json.dumps({"variables": variables}))


def evaluate(expression, do_trunc):
    """Evaluate expression in IPython namespace, print DAP JSON."""
    ipython_shell = get_ipython()
    namespace = ipython_shell.user_ns
    result = eval_in_context(expression, namespace, namespace)
    do_eval_full = not do_trunc
    var_data = _var_to_dap_dict(result, expression, evaluate_full_value=do_eval_full, context="variables")
    print(json.dumps({"variables": [var_data]}))


def get_array(text):
    """Array/DataFrame viewer — kept as XML (table_like_struct_to_xml returns XML)."""
    ipython_shell = get_ipython()
    namespace = ipython_shell.user_ns
    roffset, coffset, rows, cols, format, attrs = text.split('\t', 5)
    name = attrs.split("\t")[-1]
    var = eval_in_context(name, namespace, namespace)
    xml = table_like_struct_to_xml(var, name, int(roffset), int(coffset),
                                   int(rows), int(cols), format)
    print(xml)


def table_command(command_text):
    ipython_shell = get_ipython()
    namespace = ipython_shell.user_ns
    command, command_type, start_index, end_index, format = command_text.split(NEXT_VALUE_SEPARATOR)

    try:
        start_index = int(start_index)
        end_index = int(end_index)
    except ValueError:
        start_index = None
        end_index = None

    from pycharm_tables.pydevd_tables import exec_table_command
    status, res = exec_table_command(command, command_type, start_index, end_index, format, namespace, namespace)
    print(res)


_VARIABLE_PRESENTATION = {
    DAPGrouper.SCOPE_SPECIAL_VARS: "group",
    DAPGrouper.SCOPE_FUNCTION_VARS: "group",
    DAPGrouper.SCOPE_CLASS_VARS: "group",
    DAPGrouper.SCOPE_PROTECTED_VARS: "inline",
}


def _var_to_dap_dict(val, name, evaluate_full_value=True, context=None):
    """
    Convert a Python value to a DAP Variable dict.
    Mirrors _AbstractVariable.get_var_data() from pydevd_suspended_frames.py
    but without needing a py_db or SuspendedFramesManager.
    """
    safe_repr = SafeRepr()
    if context == "variables":
        safe_repr.maxcollection = (10**6, 10**6)
        safe_repr.maxstring_outer = 10**9
        safe_repr.maxother_outer = 10**9
        safe_repr.maxother_inner = 10**9
    type_name, _type_qualifier, _is_exception, resolver, value = get_variable_details(
        val, evaluate_full_value=evaluate_full_value, to_string=safe_repr, context=context
    )

    var_data = {
        "name": name,
        "value": value,
        "type": type_name,
        "variablesReference": id(val) if resolver is not None else 0,
        "evaluateName": name,
    }

    if val.__class__ == DAPGrouper:
        var_data["type"] = ""

    # Add shape/dtype attributes (mirrors pydevd_suspended_frames.py:70-82)
    attributes = []

    is_raw_string = type_name in ("str", "bytes", "bytearray")
    if is_raw_string:
        attributes.append("rawString")

    if name in (GENERATED_LEN_ATTR_NAME, TOO_LARGE_ATTR):
        attributes.append("readOnly")

    try:
        if _has_attribute_safe(val, 'shape') and not callable(val.shape):
            shape = str(tuple(val.shape))
            attributes.append(f"shape: {shape}")
        elif _has_attribute_safe(val, '__len__') and not is_string(val):
            shape = str(len(val))
            attributes.append(f"shape: {shape}")

        if _has_attribute_safe(val, "dtype"):
            dtype = str(val.dtype)
            attributes.append(f"dtype: {dtype}")
    except:
        pass

    if attributes:
        var_data["presentationHint"] = {"attributes": attributes}

    return var_data


def _encode_group_expression(parent_evaluate_name, scope):
    return _GROUP_EXPRESSION_PREFIX + json.dumps(
        {"parent": parent_evaluate_name, "scope": scope},
        separators=(",", ":"),
    )

def _decode_group_expression(expression):
    if not expression.startswith(_GROUP_EXPRESSION_PREFIX):
        return None

    payload = expression[len(_GROUP_EXPRESSION_PREFIX):]
    try:
        data = json.loads(payload)
    except Exception:
        return None

    if not isinstance(data, dict):
        return None

    return data.get("parent"), data.get("scope")


def _get_entry_scope(name, value, evaluate_name, hidden_names=None):
    if hidden_names is not None and name in hidden_names:
        return DAPGrouper.SCOPE_SPECIAL_VARS

    if _is_hidden_var_like_old_pydev(value):
        return DAPGrouper.SCOPE_SPECIAL_VARS

    return get_var_scope(name, value, evaluate_name or "", False)


def _iter_namespace_entries(namespace):
    return [(name, namespace[name], name) for name in sorted(namespace)]


def _group_entries(entries, parent_evaluate_name=None, hidden_names=None):
    grouped_entries = []
    scope_to_grouper = {}
    inline_entries = []

    for name, value, evaluate_name in entries:
        scope = _get_entry_scope(name, value, evaluate_name, hidden_names=hidden_names)
        if not scope:
            inline_entries.append((name, value, evaluate_name))
            continue

        presentation = _VARIABLE_PRESENTATION.get(scope, "group")
        if presentation == "hide":
            continue
        if presentation == "inline":
            inline_entries.append((name, value, evaluate_name))
            continue

        grouper = scope_to_grouper.get(scope)
        if grouper is None:
            grouper = DAPGrouper(scope)
            scope_to_grouper[scope] = grouper
            grouped_entries.append((scope, grouper, _encode_group_expression(parent_evaluate_name, scope)))

        grouper.contents_debug_adapter_protocol.append((name, value, evaluate_name))

    grouped_entries.sort(key=lambda entry: DAPGrouper.SCOPES_SORTED.index(entry[0]))
    inline_entries.sort(key=lambda entry: sorted_attributes_key(entry[0]))
    return grouped_entries + inline_entries


def _build_dap_variables(entries, hidden_names=None, parent_evaluate_name=None):
    variables = []
    for name, value, evaluate_name in _group_entries(
        entries,
        parent_evaluate_name=parent_evaluate_name,
        hidden_names=hidden_names,
    ):
        if value.__class__ == DAPGrouper:
            value.contents_debug_adapter_protocol.sort(key=lambda entry: sorted_attributes_key(entry[0]))

        eval_full = should_evaluate_full_value(value)
        var_data = _var_to_dap_dict(value, name, evaluate_full_value=eval_full)
        if evaluate_name is not None:
            var_data["evaluateName"] = evaluate_name
        variables.append(var_data)
    return variables


def _resolve_group_entries(namespace, parent_evaluate_name, scope, hidden_names=None):
    if parent_evaluate_name is None:
        entries = _iter_namespace_entries(namespace)
    else:
        val_dict = resolve_compound_var_object_fields(namespace, parent_evaluate_name) or {}
        entries = [
            (name, val_dict[name], None)
            for name in sorted(val_dict, key=sorted_attributes_key)
        ]

    return [
        (name, value, evaluate_name)
        for name, value, evaluate_name in entries
        if _get_entry_scope(name, value, evaluate_name, hidden_names=hidden_names) == scope
    ]


def load_full_value(scope_attrs):
    """Load full (non-truncated) values for specific variables."""
    ipython_shell = get_ipython()
    namespace = ipython_shell.user_ns
    parts = scope_attrs.split(NEXT_VALUE_SEPARATOR)


    variables = []
    for var_attrs in parts:
        var_attrs = var_attrs.strip()
        if not var_attrs:
            continue
        if '\t' in var_attrs:
            name, attrs = var_attrs.split('\t', 1)
        else:
            name = var_attrs
            attrs = None

        if name in namespace:
            var_object = resolve_var_object(namespace[name], attrs)
        else:
            var_object = eval_in_context(name, namespace, namespace)

        variables.append(_var_to_dap_dict(var_object, name, evaluate_full_value=True))

    print(json.dumps({"variables": variables}))


def image_load_chunk_table_command(command_text):
    try:
        ipython_shell = get_ipython()
        namespace = ipython_shell.user_ns
        command, command_type, offset, image_id = command_text.split(NEXT_VALUE_SEPARATOR)

        try:
            offset = int(offset)
        except ValueError:
            offset = None

        status, res = exec_image_table_command(command, command_type, offset, image_id, namespace, namespace)
        print(res)
    except:
        pass

def image_start_load_table_command(command_text):
    ipython_shell = get_ipython()
    namespace = ipython_shell.user_ns
    command, command_type, _ = command_text.split(NEXT_VALUE_SEPARATOR)
    status, res = exec_image_table_command(command, command_type, None, None, namespace, namespace)
    print(res)



def serializeImage(img):
    """Serialize a 2D numpy array as JSON image data."""
    if len(img.shape) != 2:
        return None
    if img.shape[0] > 1024 or img.shape[1] > 1024:
        return None
    return json.dumps({
        "height": img.shape[0],
        "width": img.shape[1],
        "value": img.tolist(),
    })


def image_command(command_text):
    ipython_shell = get_ipython()
    namespace = ipython_shell.user_ns
    try:
        var_value = eval_in_context(command_text, namespace, namespace)
        json_result = serializeImage(var_value)
    except Exception as e:
        print(e)
    print(command_text)
    print(json_result)


def _has_attribute_safe(obj, attr_name):
    """Check attribute existence without accessing it."""
    return inspect.getattr_static(obj, attr_name, None) is not None


def __get_type_name(value):
    try:
        type_name = value.__class__.__name__
    except Exception:
        try:
            type_name = type(value).__name__
        except Exception:
            type_name = None
    return type_name


def _is_hidden_var_like_old_pydev(value):
    type_name = __get_type_name(value)
    return type_name in _HIDDEN_TYPES


def __extend_hidden_vars(user_ns):
    additional_values = {}
    for k, v in user_ns.items():
        if _is_hidden_var_like_old_pydev(v):
            additional_values[k] = v

    return additional_values


def _build_compound_dap_variables(namespace, parent_evaluate_name):
    try:
        value = resolve_var_object(namespace, parent_evaluate_name)
    except Exception:
        return []

    _type, type_name, resolver = get_type(value)
    if resolver is None:
        return []

    if not hasattr(resolver, "get_contents_debug_adapter_protocol"):
        return []

    raw_entries = resolver.get_contents_debug_adapter_protocol(value, None)

    # Resolve relative/callable evaluate names to absolute.
    entries = []
    for key, child_value, evaluate_name in raw_entries:
        if evaluate_name is not None:
            if callable(evaluate_name):
                evaluate_name = evaluate_name(parent_evaluate_name)
            elif parent_evaluate_name is not None:
                evaluate_name = parent_evaluate_name + evaluate_name
        entries.append((key, child_value, evaluate_name))

    # Group routines/dunders into DAPGrouper scopes (mirrors pydevd_suspended_frames.py).
    # Inline entries preserve their original order from get_contents_debug_adapter_protocol.
    scope_to_grouper = {}
    group_entries = []
    inline_entries = []
    for key, child_value, evaluate_name in entries:
        scope = _get_entry_scope(key, child_value, evaluate_name)
        if scope:
            presentation = _VARIABLE_PRESENTATION.get(scope, "group")
            if presentation == "hide":
                continue
            elif presentation == "inline":
                inline_entries.append((key, child_value, evaluate_name))
            else:
                if scope not in scope_to_grouper:
                    grouper = DAPGrouper(scope)
                    scope_to_grouper[scope] = grouper
                    group_entries.append((scope, grouper))
                scope_to_grouper[scope].contents_debug_adapter_protocol.append(
                    (key, child_value, evaluate_name)
                )
        else:
            inline_entries.append((key, child_value, evaluate_name))

    group_entries.sort(key=lambda e: DAPGrouper.SCOPES_SORTED.index(e[0]))

    variables = []
    for scope, grouper in group_entries:
        grouper.contents_debug_adapter_protocol.sort(
            key=lambda e: sorted_attributes_key(e[0])
        )
        evaluate_name = _encode_group_expression(parent_evaluate_name, scope)
        eval_full = should_evaluate_full_value(grouper)
        var_data = _var_to_dap_dict(grouper, scope, evaluate_full_value=eval_full)
        var_data["evaluateName"] = evaluate_name
        variables.append(var_data)

    for key, child_value, evaluate_name in inline_entries:
        eval_full = should_evaluate_full_value(child_value)
        var_data = _var_to_dap_dict(child_value, key, evaluate_full_value=eval_full)
        if evaluate_name is None:
            var_data.pop("evaluateName", None)
        else:
            var_data["evaluateName"] = evaluate_name
        variables.append(var_data)

    return variables


