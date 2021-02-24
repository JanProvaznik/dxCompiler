version 1.1

import "tumor_normal.wdl" as tn

workflow complex_manifest {
  input {
    Map[String, File] samples
  }

  scatter (sample in as_pairs(samples)) {
    call tn.wf as tumor {
      input:
        id = sample.left,
        file = sample.right
    }

    if (sample.left == "normal") {
      call tn.wf as normal {
        input:
          id = sample.left,
          file = sample.right
      }
    }
  }

  call report {
    input:
      tumor = tumor.out,
      normal = normal.out
  }

  output {
    Array[File] tumor_result = tumor.out
    Array[File?] normal_result = normal.out
    File report_result = report.out
  }
}

task report {
  input {
    Array[File] tumor
    Array[File?] normal
  }

  Array[File] all_normals = select_all(normal)

  command <<<
    head ~{tumor[0]} >> report.txt
    head ~{all_normals[0]} >> report.txt
  >>>

  output {
    File out = "report.txt"
  }
}