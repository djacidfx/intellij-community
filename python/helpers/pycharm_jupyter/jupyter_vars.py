def evaluate(expression, do_trim):
    try:
        ipython_shell = get_ipython()
        from debugpy._vendored.pydevd._pydevd_bundle.pydevd_vars import eval_in_context
        from debugpy._vendored.pydevd._pydevd_bundle.pydevd_xml import var_to_xml
        user_type_renderers = getattr(ipython_shell, "user_type_renderers", None)
        namespace = ipython_shell.user_ns
        result = eval_in_context(expression, namespace, namespace)
        xml = "<xml>"
        xml += var_to_xml(result, expression, do_trim)
        xml += "</xml>"
        print(xml)
    except:
        pass


def get_frame(group_type):
    try:
        ipython_shell = get_ipython()
        user_ns = ipython_shell.user_ns
        hidden_ns = ipython_shell.user_ns_hidden
        from debugpy._vendored.pydevd._pydevd_bundle.pydevd_xml import frame_vars_to_xml
        user_type_renderers = getattr(ipython_shell, "user_type_renderers", None)
        xml = "<xml>"
        xml += frame_vars_to_xml(user_ns, hidden_ns)
        xml += "</xml>"
        print(xml)
    except:
        pass


def get_variable(pydev_text):
    try:
        from debugpy._vendored.pydevd._pydevd_bundle.pydevd_constants import dict_keys
        from debugpy._vendored.pydevd._pydevd_bundle.pydevd_vars import resolve_compound_var_object_fields
        from debugpy._vendored.pydevd._pydevd_bundle.pydevd_xml import var_to_xml, should_evaluate_full_value
        ipython_shell = get_ipython()
        user_type_renderers = getattr(ipython_shell, "user_type_renderers", None)
        val_dict = resolve_compound_var_object_fields(ipython_shell.user_ns, pydev_text, user_type_renderers)
        if val_dict is None:
            val_dict = {}

        xml_list = ["<xml>"]
        for k in dict_keys(val_dict):
            val = val_dict[k]
            evaluate_full_value = should_evaluate_full_value(val)
            xml_list.append(var_to_xml(val, k, evaluate_full_value=evaluate_full_value, user_type_renderers=user_type_renderers))
        xml_list.append("</xml>")
        print(''.join(xml_list))
    except:
        pass


def get_array(text):
    try:
        from debugpy._vendored.pydevd._pydevd_bundle.pydevd_vars import eval_in_context, table_like_struct_to_xml
        ipython_shell = get_ipython()
        namespace = ipython_shell.user_ns
        roffset, coffset, rows, cols, format, attrs = text.split('\t', 5)
        name = attrs.split("\t")[-1]
        var = eval_in_context(name, namespace, namespace)
        xml = table_like_struct_to_xml(var, name, int(roffset), int(coffset), int(rows), int(cols), format)
        print(xml)
    except:
        pass


def load_full_value(scope_attrs):
    try:
        from debugpy._vendored.pydevd._pydevd_bundle.pydevd_constants import NEXT_VALUE_SEPARATOR
        from debugpy._vendored.pydevd._pydevd_bundle.pydevd_xml import var_to_xml
        from debugpy._vendored.pydevd._pydevd_bundle.pydevd_vars import resolve_var_object, eval_in_context
        ipython_shell = get_ipython()
        user_type_renderers = getattr(ipython_shell, "user_type_renderers", None)
        namespace = ipython_shell.user_ns
        vars = scope_attrs.split(NEXT_VALUE_SEPARATOR)
        xml_list =  ["<xml>"]
        for var_attrs in vars:
            var_attrs = var_attrs.strip()
            if len(var_attrs) == 0:
                continue
            if '\t' in var_attrs:
                name, attrs = var_attrs.split('\t', 1)
            else:
                name = var_attrs
                attrs = None
            if name in namespace.keys():
                var_object = resolve_var_object(namespace[name], attrs)
            else:
                var_object = eval_in_context(name, namespace, namespace)

            xml_list.append(var_to_xml(var_object, name, evaluate_full_value=True, user_type_renderers=user_type_renderers))
        xml_list.append("</xml>")
        print(''.join(xml_list))
    except:
        pass

def table_command(command_text):
    try:
        from debugpy._vendored.pydevd._pydevd_bundle.pydevd_constants import NEXT_VALUE_SEPARATOR
        from debugpy._vendored.pydevd._pydevd_bundle.pydevd_tables import exec_table_command
        ipython_shell = get_ipython()
        namespace = ipython_shell.user_ns
        command, command_type, start_index, end_index, format = command_text.split(NEXT_VALUE_SEPARATOR)

        try:
            start_index = int(start_index)
            end_index = int(end_index)
        except ValueError:
            start_index = None
            end_index = None

        status, res = exec_table_command(command, command_type, start_index, end_index, format, namespace, namespace)
        print(res)
    except:
        pass


def image_start_load_table_command(command_text):
    try:
        from debugpy._vendored.pydevd._pydevd_bundle.pydevd_constants import NEXT_VALUE_SEPARATOR
        from debugpy._vendored.pydevd._pydevd_bundle.pydevd_tables import exec_image_table_command
        ipython_shell = get_ipython()
        namespace = ipython_shell.user_ns
        command, command_type, _ = command_text.split(NEXT_VALUE_SEPARATOR)
        status, res = exec_image_table_command(command, command_type, None, None, namespace, namespace)
        print(res)
    except:
        pass


def image_load_chunk_table_command(command_text):
    try:
        from debugpy._vendored.pydevd._pydevd_bundle.pydevd_constants import NEXT_VALUE_SEPARATOR
        from pycharm_tables.pydevd_tables import exec_image_table_command
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

def serializeImage(img):
    try:
        if len(img.shape) != 2:
            return None

        if img.shape[0] > 1024 or img.shape[1] > 1024:
            return None

        result = "{\n"
        result += "  \"height\": {},\n  \"width\": {},\n  \"value\": [\n".format(img.shape[0], img.shape[1])

        for y in range(img.shape[0]):
            result += "    ["
            for x in range(img.shape[1]):
                result += "{}".format(img[y][x])
                if x < img.shape[1] - 1:
                    result += ", "
            result += "]"
            if y < img.shape[0] - 1:
                result += ", \n"

        result += "\n  ]\n}"

        return result
    except:
        pass

def image_command(command_text):
    try:
        from debugpy._vendored.pydevd._pydevd_bundle.pydevd_vars import eval_in_context
        ipython_shell = get_ipython()
        namespace = ipython_shell.user_ns
        try:
            var_value = eval_in_context(command_text, namespace, namespace)
            json_result = serializeImage(var_value)
        except Exception as e:
            print(e)
        print(command_text)
        print(json_result)
    except:
        pass


def set_user_type_renderers(message):
    try:
        from debugpy._vendored.pydevd._pydevd_bundle.pydevd_user_type_renderers import parse_set_type_renderers_message
        ipython_shell = get_ipython()
        renderers = parse_set_type_renderers_message(message)
        ipython_shell.user_type_renderers = renderers
    except:
        pass
