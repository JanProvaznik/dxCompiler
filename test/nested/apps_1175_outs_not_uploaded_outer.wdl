version 1.0
import "apps_1175_outs_not_uploaded_inner.wdl" as nested_inner

workflow apps_1175_outs_not_uploaded_outer {
  input {
    String s
  }
  call nested_inner.nested_inner as ni { input: s = "~{s+s}" }
  output {
    File out_inner = ni.nested_inner_wf_out
  }
}