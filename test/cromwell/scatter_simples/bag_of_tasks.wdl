version 1.0

task A {
  command {
    echo -n -e "jeff\nchris\nmiguel\nthibault\nkhalid\nruchi"
  }
  runtime {
    docker: "dx://file-G66qz3Q0yzZfy6pg5q3yK3Kz"
  }
  output {
    Array[String] A_out = read_lines(stdout())
  }
}

task B {
  input {
    String B_in
  }
  command {
    python -c "print(len('~{B_in}'))"
  }
  runtime {
    docker: "dx://file-G66qz3Q0yzZfy6pg5q3yK3Kz"
  }
    output {
    Int B_out = read_int(stdout())
  }
}

task C {
  input {
    Int C_in
  }
  command {
    python -c 'print(~{C_in}*100)'
  }
  runtime {
    docker: "dx://file-G66qz3Q0yzZfy6pg5q3yK3Kz"
  }
  output {
    Int C_out = read_int(stdout())
  }
}

task D {
  input {
    Array[Int] D_in
  }
  command {
    python -c 'print(~{sep='+' D_in})'
  }
  runtime {
    docker: "dx://file-G66qz3Q0yzZfy6pg5q3yK3Kz"
  }
  output {
    Int D_out = read_int(stdout())
  }
}

task E {
  command {
    python -c 'print(9)'
  }
  runtime {
    docker: "dx://file-G66qz3Q0yzZfy6pg5q3yK3Kz"
  }
  output {
    Int E_out = read_int(stdout())
  }
}
