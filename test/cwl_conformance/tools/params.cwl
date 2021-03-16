class: CommandLineTool
cwlVersion: v1.2
inputs:
  bar:
    type: Any
    default: {
      "baz": "zab1",
      "b az": 2,
      "b'az": true,
      'b"az': null,
      "buz": ['a', 'b', 'c']
    }

outputs:
- id: t1
  type: Any
  outputBinding:
    outputEval: $(inputs)
- id: t2
  type: Any
  outputBinding:
    outputEval: $(inputs.bar)
- id: t3
  type: Any
  outputBinding:
    outputEval: $(inputs['bar'])
- id: t4
  type: Any
  outputBinding:
    outputEval: $(inputs["bar"])

- id: t5
  type: Any
  outputBinding:
    outputEval: $(inputs.bar.baz)
- id: t6
  type: Any
  outputBinding:
    outputEval: $(inputs['bar'].baz)
- id: t7
  type: Any
  outputBinding:
    outputEval: $(inputs['bar']["baz"])
- id: t8
  type: Any
  outputBinding:
    outputEval: $(inputs.bar['baz'])

- id: t9
  type: Any
  outputBinding:
    outputEval: $(inputs.bar['b az'])
- id: t10
  type: Any
  outputBinding:
    outputEval: $(inputs.bar['b\'az'])
- id: t11
  type: Any
  outputBinding:
    outputEval: $(inputs.bar["b'az"])
- id: t12
  type: "null"
  outputBinding:
    outputEval: $(inputs.bar['b"az'])

- id: t13
  type: Any
  outputBinding:
    outputEval: -$(inputs.bar.baz)
- id: t14
  type: Any
  outputBinding:
    outputEval: -$(inputs['bar'].baz)
- id: t15
  type: Any
  outputBinding:
    outputEval: -$(inputs['bar']["baz"])
- id: t16
  type: Any
  outputBinding:
    outputEval: -$(inputs.bar['baz'])

- id: t17
  type: Any
  outputBinding:
    outputEval: $(inputs.bar.baz) $(inputs.bar.baz)
- id: t18
  type: Any
  outputBinding:
    outputEval: $(inputs['bar'].baz) $(inputs['bar'].baz)
- id: t19
  type: Any
  outputBinding:
    outputEval: $(inputs['bar']["baz"]) $(inputs['bar']["baz"])
- id: t20
  type: Any
  outputBinding:
    outputEval: $(inputs.bar['baz']) $(inputs.bar['baz'])

- id: t21
  type: Any
  outputBinding:
    outputEval: $(inputs.bar['b az']) $(inputs.bar['b az'])
- id: t22
  type: Any
  outputBinding:
    outputEval: $(inputs.bar['b\'az']) $(inputs.bar['b\'az'])
- id: t23
  type: Any
  outputBinding:
    outputEval: $(inputs.bar["b'az"]) $(inputs.bar["b'az"])
- id: t24
  type: Any
  outputBinding:
    outputEval: $(inputs.bar['b"az']) $(inputs.bar['b"az'])

- id: t25
  type: Any
  outputBinding:
    outputEval: $(inputs.bar.buz[1])
- id: t26
  type: Any
  outputBinding:
    outputEval: $(inputs.bar.buz[1]) $(inputs.bar.buz[1])

- id: t27
  type: "null"
  outputBinding:
    outputEval: $(null)

- id: t28
  type: int
  outputBinding:
    outputEval: $(inputs.bar.buz.length)

baseCommand: "true"