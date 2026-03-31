load("@rules_jvm//:jvm.bzl", "ResourceGroupInfo")
load("@rules_java//java:defs.bzl", "JavaInfo")
load("@rules_kotlin//kotlin/internal:defs.bzl", KOTLIN_TOOLCHAIN = "TOOLCHAIN_TYPE")
load("//fleet/build/rules:haven_cli.bzl", "HAVEN_CLI_ATTR", "run_haven_cli")

def _fleet_plugin_services_resources_impl(ctx):
    resources_output_dir = ctx.actions.declare_directory("%s.generated_resources" % ctx.label.name)

    compile_classpath = depset(
        transitive = [
            dep[JavaInfo].transitive_compile_time_jars
            for dep in ctx.attr.deps
        ],
    )
    processor_classpath = ctx.attr._kernel_plugins_processor[JavaInfo].transitive_runtime_jars
    kotlin_toolchain = ctx.toolchains[KOTLIN_TOOLCHAIN]
    module_name = ctx.attr.module_name if ctx.attr.module_name else ctx.attr.name
    args = ctx.actions.args()
    args.set_param_file_format("multiline")
    args.use_param_file("--flagfile=%s", use_always = True)
    args.add("generate-fleet-plugin-services-resources")
    args.add("--module-name=%s" % module_name)
    args.add_all(["--sources=%s" % s.path for s in ctx.files.srcs])
    args.add_all(["--classpath=%s" % c.path for c in compile_classpath.to_list()])
    args.add_all(["--processor-classpath=%s" % c.path for c in processor_classpath.to_list()])
    args.add("--jvm-target=%s" % kotlin_toolchain.jvm_target)
    args.add("--language-version=%s" % kotlin_toolchain.language_version)
    args.add("--api-version=%s" % kotlin_toolchain.api_version)
    args.add("--output-dir=%s" % resources_output_dir.path)

    run_haven_cli(
        ctx = ctx,
        mnemonic = "GenerateFleetPluginServicesResources",
        inputs = depset(
            direct = ctx.files.srcs,
            transitive = [compile_classpath, processor_classpath],
        ),
        outputs = [resources_output_dir],
        arguments = [args],
        progress_message = "Generating Fleet plugin services resources for %%{label}",
    )

    return [
        DefaultInfo(files = depset([resources_output_dir])),
        ResourceGroupInfo(files = [resources_output_dir], strip_prefix = resources_output_dir.path, add_prefix = ""),
    ]

fleet_plugin_services_resources = rule(
    implementation = _fleet_plugin_services_resources_impl,
    attrs = HAVEN_CLI_ATTR | {
        "srcs": attr.label_list(
            allow_files = True,
            mandatory = True,
            doc = "Source files used to derive Fleet plugin service resources.",
        ),
        "deps": attr.label_list(
            providers = [JavaInfo],
            doc = "Compile classpath for the analyzed sources.",
        ),
        "module_name": attr.string(
            doc = "Kotlin module name for the analyzed sources.",
        ),
        "_kernel_plugins_processor": attr.label(
            default = "//fleet/build/kernel.plugins.processor",
            providers = [JavaInfo],
        ),
    },
    toolchains = [KOTLIN_TOOLCHAIN],
)

