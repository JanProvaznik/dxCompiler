# test-dxcompiler-in-project Readme

Applet `test-dxcompiler-in-project` can be built and deployed in customer-provided projects to facilitate testing on their WDL workflows.

## Test Project Setup

- The [example_project directory](./example_project) shows the recommended structure for a test project.
- `test/test_dxcompiler.sh` should be a script that downloads the workflow definition files onto the worker, compiles the workflows, and runs them.
- It is recommended to have 1 `workflow_#` folder per test workflow containing all files necessary to compile the workflow.
- It is recommended to direct compiled artifacts and workflow outputs to subfolders in `test_out/`.
- Input data files should be kept elsewhere; they do not need to be downloaded for compilation.

## Test Project Access

- Customer projects should be shared with `org-dnanexus_apps_customer_testers`, level: `CONTRIBUTE`.

## Building & Running the Applet

- Run the dxCompiler release GitHub workflow; this uploads dxCompiler assets to the platform.
- Set the dxCompiler download URL in `test-dxcompiler-in-project/Makefile`.
- Run the script `scripts/run_customer_tests`.
- You will need to be a member of `org-dnanexus_apps_customer_testers` to access the projects.
