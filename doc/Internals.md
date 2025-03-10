# dxCompiler Architecture

dxCompiler is a Scala application that performs two functions:

1. Compiler: translates tasks/workflows written in a supported language (WDL or CWL) into native DNAnexus applets/workflows.
2. Executor: Executes tasks and workflow constructs (such as scatter/gather) within the applets generated by the compiler, in a language-specific manner.

## Compiler

The compiler has three phases:

1. Parsing: The source code written in a workflow language (WDL or CWL) is parsed into an abstract syntax tree (AST). This depends on a language-specific library:
    * [wdlTools](https://github.com/dnanexus/wdlTools) for WDL, which is based on the [OpenWDL](https://github.com/openwdl/wdl) ANTLR4 grammar.
    * [cwlScala](https://github.com/dnanexus/cwlScala) for CWL, which is based on [cwljava](https://github.com/common-workflow-lab/cwljava).
2. Translation: The language-specific AST is translated into dxCompiler "Intermediate Representation" (IR), which mirrors the structure of DNAnexus applets/workflows, including DNAnexus-supported data types, metadata, and runtime specifications. The output of this step is an IR "Bundle".
3. Compilation: Generates native applets and workflows from the IR. The output of this step is the applet ID (for a source file that contains a single tool/task) or workflow ID.

The compiler can be invoked programmatically, but it is most common to use it via the [command line interface](../compiler/src/main/scala/dxCompiler/Main.scala).

### Translation

The first step in translation is to determine the language in which the source file is written and create an instance of the appropriate [1Translator1](../compiler/src/main/scala/dx/translator/Translator.scala) subclass. This is done using the `TranslatorFactory.createTranslator` function, which attempts to invoke the `create` method on each `TranslatorFactory` instance (there is one for each language) and returns the result of the first one that is successful.

The `Translator.apply` method is invoked to perform the translation and returns a [`Bundle`](../core/src/main/scala/dx/core/ir/package.scala). Translation is performed in a language-specific manner, but it generally follows these steps:

1. The source file is parsed into an AST using the language-specific parser. The WDL parser performs type-checking while the CWL parser does not. The result of parsing is a language-specific "bundle", which consists of a `Map` of all the executables (tasks/tools or workflows) contained within the source file and a pointer to the "primary" executable, which is determined in a language-specific manner:
   * In WDL, the primary executable is the single workflow or task in the main source file
   * In CWL, the primary executable has ID of "main". Failing that, the primary executable is the top-level workflow or tool in the source file. Failing that, ff the source file is a `$graph` structure, and the graph contains a single workflow or tool, that will be primary executable.
2. The executables in the bundle are sorted in dependency order. Starting from the primary executable, all the dependencies of the executable are determined. That process continues recursively until all dependencies have been resolved. The resulting list is ordered such that each executable appears before any of the executables that depend on it.
3. Each executable in the sorted list is translated into "Intermediate Representation" (IR). The results of the translation process are collected into the an IR `Bundle`.

#### Task/tool translation

A WDL task or CWL tool is translated into an [`Application`](../core/src/main/scala/dx/core/ir/package.scala) instance.

Task/tool inputs and outputs are translated to native DNAnexus inputs/outputs. For the most part the translation is straight-forward, but there are a few important nuances:

* CWL allows parameter names to contain characters that are not allowed by DNAnexus. CWL parameter names are "encoded" using a process described in [`CwlDxName`](../core/src/main/scala/dx/core/languages/cwl/CwlDxName.scala).
* Both WDL and CWL have data types for which there is no DNAnexus equivalent. The values of these types are represented using native DNAnexus types, but interpreting them always requires the type information.
  * CWL `Enum`s are represented using a DNAnexus `string`. At compile time, the allowed values of an enum-typed parameter is limited using DNAnexus `choices`.
  * CWL `File` and `Directory` types can carry more information than just the file/directory URI. Thus, CWL files and directories are always represented as DNAnexus `hash`es. In WDL, the `File` type is represented as DNAnexus `file` and the `Directory` type is represented as a `string`.
  * The other types are represented using a DNAnexus `hash` where the value must be a hash that must have a single key, `___`, mapped to the actual value. This is referred to as a "wrapped" value.
    * WDL `Struct`s and CWL `Record`s are user-defined data types. These are generically referred to as `Schema` types in dxCompiler.
    * CWL allows union types which indicate that a value can be any type out of a list of allowed types.
    * WDL `Map` is represented as a hash with two keys, `keys` and `values`, which are lists of equal length.
    * WDL `Pair` is represented as a hash with two keys, `left` and `right`.
    * These types are actually represented by two separate input/output fields in the DNAnexus applet - one named with the parameter name, and one named with `<name>___dxfiles` that is array of all the DNAnexus files contained within the actual value at any level of nesting. This is required for the API to properly stage input files and close output files.
* If the parameter is defined with a value (an expression in WDL or a default value in CWL), the value is evaluated statically. If the value is successfully evaluated, then the result is used as the `default` value of the DNAnexus parameter.

A task/tool may specify runtime requirements or hints, which are eventually compiled into the `runSpec` and other sections of the applet's `dxapp.json` file. For the most part the translation is straight-forward, but there are a few important details:

* Resource requirements/hints, such as number of CPUs, amount of RAM, and amount of disk space, can be specified in one of three ways:
   * Hard-coded DNAnexus instance type (currently WDL only).
   * Dynamic values, which are written as expressions and must be evaluated within the runtime context.
   * Static values (e.g. `1 cpu`). If all of the resource requirements are static, then the user chooses (with a CLI option) whether they want the instance type to be determined at compile time or runtime. Runtime evaluation is safest, but there are performance benefits to compile-time evaluation that a user can take advantage of so long as they can guarantee that the same instance types that are available at compile time will always be available at runtime.
* Docker images may be specified in one of three ways:
    * An image name that is pulled from a public repository.
    * An image name that is pulled from a private repository - this requires the user to specify the repository credentials in the `extras.json` file at compile time (see [Expert Options](ExpertOptions.md)).
    * A file (created using `docker save`) stored in a DNAnexus project - this requires the file to be available at runtime. This is specified as a `dx://project-xxx:file-yyy` URI.

An IR application has the language-specific source code attached to it because this is used to execute the task/tool at runtime. The source code is compressed, base64-encoded, and stored in the `details` of the generated applet. In WDL, a "stand-alone" task is generated, which means that the task (and any dependencies, such as `Struct`s that are used as input/output parameter types) is extracted from the WDL bundle and used to generate new source code (using the `wdlTools.generator.code.WdlGenerator` class in wdlTools).

#### Workflow translation

The first step in translating a workflow is to break it into "blocks". A block is a subset of workflow elements that can be evaluated together in the same context. Most importantly, a block references at most one executable, which may be nested within scatters and/or conditionals.
    * In WDL, an executable is referenced using a `call` statement, which may invoke a task or workflow. A block may include non-call statements that immediately preceed the call, such as private variable declarations (which themselves may be nested in scatter or conditional blocks, so long as those blocks do not contain any calls).
    * In CWL an executable is referenced in a workflow `step`, which may invoke a tool or a workflow in it's `run` section.

A block has inputs and outputs. The inputs are all the variables that are needed to invoke the executable, which includes evaluating any expressions. The outputs are all the variables that exist in the environment after evaluating the block. This includes the outputs of the executable. If the executable is nested within a conditional, then the types of the block outputs corresponding to the executable outputs become optional, and if the executable is nested within a scatter, then the types become arrays (these transformations are applied recursively for each level of nesting). In WDL, the block outputs also include any private variables included in the block.

Once the blocks are created, then each block is translated in order in one of two ways:

* A simple block is translated directly to a stage in the DNAnexus workflow.
    * In WDL, a simple block consists of just a `call` statement that is not nested within any conditional or scatter blocks and does not require any runtime evaluation, i.e. each input to the executable referenced by the `call` is either a static value or a reference to a workflow input or to the output of a previous block.
    * In CWL, a simple block is a workflow step that does not have a `when` or `scatter` clause, and whose inputs do not have any `valueFrom` clauses.
* Any other block is translated into a "fragment" (or "frag"), which is a helper applet that evaluates any expressions (including conditionals) and handles scatters. The fragment applet is added as a stage in the DNAnexus workflow.

There are three other special applets that may be generated for a workflow: common, output, and reorg.

* A Common applet handles evaluation of any expressions in the workflow inputs. The common applet also plays an important role when using manifests (see the separate section on Manifests).
* An Output applet handles evaluation of any expressions in the workflow outputs.
* A Reorg applet runs at the very end of the workflow and can be used to reorganize the workflow outputs in the project, include deletion of intermediate outputs.

The translation of the workflow is slightly different depending on whether a locked or unlocked workflow is being created. A locked workflow requires that all workflow inputs and outputs are explicitly defined, whereas in an unlocked workflow inputs are specified as inputs to workflow stages. An unlocked workflow always has Common and Output applets, whereas a locked workflow only has Common/Output applets in certains cases (such as when there are expressions that must be evaluated). It is important to note that, in an unlocked workflow, only the top-level workflow is unlocked, while any nested workflows are always locked.

#### Input translation

Once a `Bundle` has been created, it can be used in conjunction with the `Translator` instance to translate standard input files into the format required to invoke the native DNAnexus applet/workflow that would be generated by the compilation step. This is performed by calling the `Translator.translateInputs` method.

### Compilation

An IR bundle is compiled into equivalent DNAnexus workflows and applets.

* Each tool/task is compiled into a native applet, which is essentially a call to the `applet/new` API method. The applet code is generated from one of the [templates](../compiler/src/main/resources/templates), which are written in [SSP](https://scalate.github.io/scalate/documentation/ssp-reference.html).
* Each workflow is compiled into a native workflow, which is essentially a call to the `workflow/new` API method. Each workflow stage references either a tool/task applet, a "frag" applet, or another workflow.

Compilation for each applet/workflow is performed in two steps. First, the IR `Application` is translated to a JSON document, which is the payload of the API call. The JSON document is hashed to generate a digest. The compiler then searches the project to see if there is an existing applet with that digest. If there is, then that applet is reused. Otherwise, the digest is added to the `details` of the document and then the API call is performed to create the applet.

The compiler is also responsible for resolving the asset bundle that is required to execute applets at runtime and adding it as a dependency of each applet, which will cause it to be automatically staged on the worker at runtime. The asset bundle consists of the Java runtime, the JAR containing the executor code (described in the next section), and any other dependencies.

#### Examining the compilation output

After compiling your workflow to a project, you will see the compiled workflows and applets in
the project like this:

```
my_task_1
my_task_2
my_workflow
my_workflow_common
my_workflow_outputs
```

To look inside the generated job script for an applet, you can run e.g. `dx get my_task_1`. This will download a folder called `my_task_1` with the applet job script inside `my_task_1/src`.

The workflow source code (after some processing during compilation, not necessarily matching the input WDL/CWL) can be extracted from a compiled workflow / compiled task applets using `scripts/extract_source_code.sh` (First have installed: `base64`, `jq`, `gzip`).

After compiling, you will also see `dxWDLrt : record-xxxx` (or `dxCWLrt`) in the project. This record points to the asset bundle of resources published to the platform during each dxCompiler release. It contains `dxExecutorWdl.jar` (or `dxExecutorCwl.jar` for CWL), which is needed during workflow execution.

## Executor

The executor is an instance of [TaskExecutor](../executorCommon/src/main/scala/dx/executor/TaskExecutor.scala) for tool/task applets, and an instance of [WorkflowExecutor](../executorCommon/src/main/scala/dx/executor/WorkflowExecutor.scala) for frag applets. The applet bash script invokes the executor.

Both executors rely on [JobMeta](../executorCommon/src/main/scala/dx/executor/JobMeta.scala), which encapsulates all of the job and application metadata.

### Task Executor

The TaskExecutor is responsible for all the steps necessary to execute a task/tool, including downloading any input files and uploading any output files. In detail, the execution steps are:

1. Evaluate all task/tool inputs. Also evaluate any WDL private variables.
2. Evaluate any runtime requirements and determine which instance type is required to run the task/tool. If the current instance type is not sufficient, then the job is re-launched with the new instance type.
3. Extract the input files that need to be localized to the worker. Note that a directory input is converted to a list of files.
4. Write dxda and/or dxfuse manifests (depending on the global and file-specific streaming settings).
5. Invoke dxda and/or dxfuse by calling the corresponding functions in the applet bash script (`download_dxda` and `download_dxfuse`) via a shell subprocess.
6. Update the inputs to replace remote file URIs with the localized paths.
7. Evaluate the task's command block to replace all interpolation placeholders with their values.
8. Write the evaluated command block to a file and make it executable.
9. If a docker image is referenced in the task/tool:
    * Download the image if it is stored as a DNAnexus platform file and load it (using `docker load`).
    * Pull the image if it is in a registry. This also peforms the login if the registry is private.
10. Invoke the command script by calling the `run_command` function in the applet bash script via a shell subprocess.
     * The stdout and stderr of process are stored to files.
     * The return code of the process is stored to a file.
11. Read the return code of the process and check if it represents success or failure, and exit if it is a failure.
12. Evaluate all task/tool outputs.
13. Extract all file paths from the evaluted outputs.
14. Upload any files that were generated by the task command.
15. Replace all local file paths in the output values with URIs.
16. Write the de-localized outputs to the `job_output.json` meta file.

### Workflow Executor

The WorkflowExecutor is responsible for executing all the steps associated with the different types of "frag" applets: Run, Continue, Collect, Common, Output, and Reorg.

A Run frag is responsible for calling another applet, including handling of conditionals and scatters. It first evaluates all the inputs. For a conditional, it evaluates the expression and only calls the applet if it evaluates to true. For a scatter, it evaluates the scatter expression and then computes the inputs for each scatter job. Each job is executed by making an API call to `applet/run`.

#### Scatters

The WDL and CWL specifications do not place a limit to the number of executions that can be invoked by a scatter. However,to limit the load on the API server, we place a limit (500) on the number of scatter jobs that can be executed simultaneously.

When a scatter is first launched, the first `N` jobs are executed, where `N` is less than or equal to the limit. This is called a "chunk" of jobs. If the total number of scatter jobs exceeds `N`, then a "continue" job is also launched.

A continue job depends on all the jobs in the chunk, which means its execution is deferred until all jobs in the chunk complete successfully. A continue job is aware of 1) the starting index of the next chunk to execute, and 2) the IDs of all the "parent" jobs who launched any previous chunks. The continue job launches the next chunk.

The process continues in this fashion until `N` is less than the limit, at which point a "collect" job is lanched rather than a "continue" job. The collect job uses the `system/findExecutions` API call to query by the parent job IDs to find and describe all the scatter jobs. It then collects the output of each parameter from each scatter job to create each final output array.

#### Reorg

TODO

## Manifests

TODO

# WDL details

## Imports and nested namespaces

A WDL file creates its own namespace. It may import other WDL files, each inhabiting its own namespace. Tasks and workflows from children can be called with their fully-qualified-names. We map the WDL namespace hierarchy to a flat space of *dx:applets* and *dx:workflows* in the target project and folder. To do this, we make sure that tasks and workflows are uniquely named.

In a complex namespace, a task/workflow can have several definitions. Such namespaces cannot be compiled by dxCompiler.

## Compiling a task

A task is compiled into an applet that has an equivalent signature. For example, a task such as:

```wdl
version 1.0

task count_bam {
  input {
    File bam
  }
  command <<<
    samtools view -c ${bam}
  >>>
  runtime {
    docker: "quay.io/ucsc_cgl/samtools"
  }
  output {
    Int count = read_int(stdout())
  }
}
```

is compiled into an applet with the following `dxapp.json`:

```json
{
  "name": "count_bam",
  "dxapi": "1.0.0",
  "version": "0.0.1",
  "inputSpec": [
    {
      "name": "bam",
      "class": "file"
    }
  ],
  "outputSpec": [
    {
      "name": "count",
      "class": "int"
    }
  ],
  "runSpec": {
    "interpreter": "bash",
    "file": "code.sh",
    "distribution": "Ubuntu",
    "release": "16.04"
  }
}
```

The `code.sh` bash script runs the docker image `quay.io/ucsc_cgl/samtools`, under which it runs the shell command `samtools view -c ${bam}`.

## A Linear Workflow

Workflow `linear` (below) takes integers `x` and `y`, and calculates `2*(x + y) + 1`. Integers are used for simplicity; more complex types such as maps or arrays could be substituted, keeping the compilation process exactly the same.

```wdl
version 1.0

workflow linear {
  input {
    Int x
    Int y
  }

  call add { input: a = x, b = y }
  call mul { input: a = add.result, b = 2 }
  call inc { input: a = mul.result }

  output {
    Int result = inc.result
  }
}

# Add two integers
task add {
  input {
  Int a
  Int b
  }
  command {}
  output {
    Int result = a + b
  }
}

# Multiply two integers
task mul {
  input {
    Int a
    Int b
  }
  command {}
  output {
    Int result = a * b
  }
}

# Add one to an integer
task inc {
  input {
    Int a
  }
  command {}
  output {
    Int result = a + 1
  }
}
```

`linear` has no expressions and no if/scatter blocks. This allows direct compilation into a dx:workflow, which schematically looks like this:

| phase   | call   | arguments |
|-------  | -----  | ----      |
| Inputs  |        |     x, y  |
| Stage 1 | applet add | x, y  |
| Stage 2 | applet mul | stage-1.result, 2 |
| Stage 3 | applet inc | stage-2.result |
| Outputs |        | sub.result |

In addition, there are three applets that can be called on their own: `add`, `mul`, and `inc`. The image below shows the workflow as an
ellipse, and the standalone applets as light blue hexagons.

![](./images/linear.png)

## Fragments

The compiler can generate applets that are able to fully process simple parts of a larger workflow. These are called *fragments*. A fragment comprises a series of declarations followed by a call, a conditional block, or a scatter block. Native workflows do not support variable lookup, expressions, or evaluation. This means that we need to launch a job even for a trivial expression. The compiler tries to batch such evaluations together, to minimize the
number of jobs. For example, workflow `linear2` is split into three stages, the last two of which are fragments.

```wdl
workflow linear2 {
  input {
    Int x
    Int y
  }

  call add { input: a=x, b=y }

  Int z = add.result + 1
  call mul { input: a=z, b=5 }

  call inc { input: i= z + mul.result + 8}

  output {
    Int result = inc.result
  }
}
```

Task `add` can be called directly, no fragment is required. Fragment-1 evaluates expression `add.result + 1`, and then calls `mul`.

```wdl
Int z = add.result + 1
call mul { input: a=z, b=5 }
```

Fragment-2 evaluates `z + mul.result + 8`, and then calls `inc`.

```wdl
call inc { input: i= z + mul.result + 8 }
```

Workflow `linear2` is compiled into:

| phase   | call   | arguments |
|-------  | -----  | ----      |
| Inputs  |        |     x, y  |
| Stage 1 | applet add | x, y  |
| Stage 2 | applet fragment-1 | stage-1.result |
| Stage 3 | applet fragment-2 | stage-2.z, stage-2.mul.result |
| Outputs |        | stage-3.result |

![](./images/linear2.png)

Workflow `optionals` uses conditional blocks. It can be broken down into two fragments.

```wdl
workflow optionals {
  input {
    Boolean flag
    Int x
    Int y
  }

  if (flag) {
    call inc { input: a=x }
  }
  if (!flag) {
    call add { input: a=x, b=y }
  }

  output {
    Int? r1 = inc.result
    Int? r2 = add.result
  }
}
```

Fragment 1:

```wdl
if (flag) {
call inc { input: a=x }
}
```

Fragment 2:

```wdl
if (!flag) {
call add { input: a=x, b=y }
}
```

The fragments are linked together into a dx:workflow like this:

| phase   | call   | arguments |
|-------  | -----  | -------   |
| Inputs  |        |     flag, x, y  |
| Stage 1 | applet fragment-1 | flag, x |
| Stage 2 | applet fragment-2 | flag, x, y |
| Outputs |        | stage-1.inc.result, stage-2.add.result |

![](./images/optionals.png)

Workflow `mul_loop` loops through the numbers *0, 1, .. n*, and multiplies them by two. The result is an array of integers.

```wdl
workflow mul_loop {
  input {
    Int n
  }

  scatter (item in range(n)) {
    call mul { input: a = item, b=2 }
  }

  output {
    Array[Int] result = mul.result
  }
}
```

It is compiled into:

| phase   | call   | arguments |
|-------  | -----  | -------   |
| Inputs  |        |     n  |
| Stage 1 | applet fragment-1 | n |
| Outputs |        | stage-1.mul.result |

The fragment is executed by an applet that calculates the WDL
expressions `range(n)`, iterates on it, and launches a child job for
each value of `item`. In order to massage the results into the proper
WDL types, we run a collect sub-job that waits for the child jobs to
complete, and returns an array of integers.

![](./images/mul_loop.png)

## Nested blocks

WDL allows blocks of scatters and conditionals to be nested arbitrarily. Such complex workflows are broken down into fragments, and tied together with subworkflows. For example, in workflow `two_levels` the scatter block requires a subworkflow that will chain together the calls `inc1`, `inc2`, and `inc3`. Note that `inc3` requires a fragment because it needs to evaluate and export declaration `b`.

```wdl
workflow two_levels {
  input {
  }

  scatter (i in [1,2,3]) {
    call inc as inc1 { input: a = i}
    call inc as inc2 { input: a = inc1.result }

    Int b = inc2.result

    call inc as inc3 { input: a = b }
  }

  if (true) {
    call add { input: a = 3, b = 4 }
  }

  call mul {input: a=1, b=4}

  output {
    Array[Int] a = inc3.result
    Int? b = add.result
    Int c = mul.result
  }
}
```

It will be broken down into five parts. A sub-workflow will tie the first three pieces together:

Part 1:
```wdl
call inc as inc1 { input: a = i}
```

Part 2:
```wdl
call inc as inc2 { input: a = inc1.result }
```

Part 3 (fragment *A*):
```wdl
Int b = inc2.result
call inc as inc3 { input: a = b }
```

The top level workflow calls a scatter applet, which calls the sub-workflow. Later,
it calls parts four and five.

Part 4 (fragment *B*):
```wdl
if (true) {
  call add { input: a = 3, b = 4 }
}
```

Part 5:
```wdl
call mul {input: a=1, b=4}
```

The overall structure is

![](./images/two_levels.png)

## Support for native DNAnexus executables

### Instance type overrides

| #   | Call type | Instance type<br/>request | Method              | Support | Implementation note                                                                                                                                                                                                      |
|-----|-----------|---------------------------|---------------------|:-------:|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1   | Direct    | Static                    | Instance name       | &check; | Supported as of `v2.10.1`                                                                                                                                                                                                |
| 2   | Direct    | Static                    | System requirements |    _    | Support with system requirements (CPU/RAM/etc) is in `v2.10.2-SNAPSHOT`                                                                                                                                                  |
| 3   | Direct    | Dynamic                   | Instance name       |    _    | Example: `mem1_ssd1_~{REMAINING_PART_OF_INSTANCE_NAME}`                                                                                                                                                                  |
| 4   | Direct    | Dynamic                   | System requirements |    _    | Example: `memory: ~{mem}GB`, where `mem` is determined elsewhere                                                                                                                                                         |
| 5   | Fragment  | Static                    | Instance name       | &check; | Implemented in `v2.10.2-SNAPSHOT`                                                                                                                                                                                        |
| 6   | Fragment  | Static                    | System requirements |    _    | Implementation follows #2 above                                                                                                                                                                                          |
| 7   | Fragment  | Dynamic                   | Instance name       |    _    | The task wrappers for native executables cannot have inputs with expressions and cannot have private variables, so the runtime expressions can only operate on the actual inputs will be passed to the native executable |
| 8   | Fragment  | Dynamic                   | System requirements |    _    | See #7                                                                                                                                                                                                                   |


#### Default instance types
- **Direct call**: Native executable uses default instance that's been defined in the executable defaults (`dxapp.json`).
- **Frag call**: Frag wrapper uses the smallest instance type of the [InstanceTypeDB](https://github.com/dnanexus/dxScala/blob/dd2c823caf206008d2de55b15da1427b21fdd31d/api/src/main/scala/dx/api/DxInstanceType.scala#L260) object. Wrapped native executable follows the rules above. 
