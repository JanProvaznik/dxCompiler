version 1.0

workflow requester_pays_engine_functions {
    File input_file = "dx://file-G3f1Y5j0yzZy5G13GVY8J78Q"
    call functions {
        input: input_string = read_string(input_file),
               input_size = size(input_file)
    }
    output {
        String result = functions.o
    }
}


task functions {
    input {
        String input_string
        Float input_size
    }
    File input_file = write_lines(["hello"])
    command {
        echo ~{input_string}
        echo ~{input_size}
    }
    runtime {
        docker: "ubuntu"
    }
    output {
        String o = read_string(stdout())
    }
}
