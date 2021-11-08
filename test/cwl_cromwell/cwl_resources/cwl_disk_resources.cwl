#!/usr/bin/env cwl-runner
cwlVersion: v1.2
$namespaces:
  dx: https://www.dnanexus.com/cwl#
$graph:
- id: diskSizeTool
  class: CommandLineTool
  cwlVersion: v1.0
  doc: "Asks for disk minimums"
  requirements:
    ResourceRequirement:
      outdirMin: 10240
      tmpdirMin: 10240
  hints:
  - class: ShellCommandRequirement
  - class: DockerRequirement
    dockerPull: gcr.io/google.com/cloudsdktool/cloud-sdk:slim
  - class: dx:InputResourceRequirement
    indirMin: 10240
  - class: NetworkAccess
    networkAccess: true
  - class: LoadListingRequirement
    loadListing: deep_listing
  inputs: []
  outputs:
    disk_size:
      type: int
      outputBinding:
        glob: stdout
        loadContents: true
        outputEval: $(parseInt(self[0].contents.trim()))
  arguments:
  - valueFrom: |
      apt-get install --assume-yes jq > /dev/null && NAME=`curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/name` && ZONE=`basename \`curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/zone\`` && PROJECT=`curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/project/project-id` && curl -s -H "Authorization: Bearer `gcloud auth print-access-token`" "https://www.googleapis.com/compute/v1/projects/${PROJECT}/zones/${ZONE}/disks/${NAME}-1?fields=sizeGb" | jq -r '.sizeGb'
    shellQuote: false
  stdout: stdout
