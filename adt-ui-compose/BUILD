load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.adt.ui.compose",
    srcs = ["src"],
    iml_files = ["intellij.android.adt.ui.compose.iml"],
    kotlin_use_compose = True,
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    exports = [
        "//prebuilts/tools/common/m2:jewel-ide",
        "//prebuilts/tools/common/m2:compose-desktop",
        "//prebuilts/tools/common/m2:jewel-standalone",
        "//prebuilts/tools/common/m2:compose-desktop-test",
    ],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "//prebuilts/tools/common/m2:jewel-ide",
        "//prebuilts/tools/common/m2:compose-desktop",
        "//prebuilts/tools/common/m2:jewel-standalone[test]",
        "//prebuilts/tools/common/m2:compose-desktop-test[test]",
        "//tools/adt/idea/.idea/libraries:jetbrains.kotlinx.coroutines.test[test]",
    ],
)
