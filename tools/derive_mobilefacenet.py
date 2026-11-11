"""Remove duplicate trunk computation; preserve the original two-row final convolutions.

Requires numpy, flatbuffers and Google's flatc v25.12.19 (object API generator).
The bundled schema is TensorFlow v2.19.0's compiler/mlir/lite/schema/schema.fbs.
No trained constant, convolution option or per-face operation is changed.
"""
import argparse
from collections import Counter
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

import flatbuffers
import numpy as np

SOURCE_SHA256 = "d8ba40c0127fb8ca9917e8fddc79bbbda063657bc92a496d34da0bc8a760443b"


def derive(source):
    from tflite.Model import Model, ModelT
    from tflite.BuiltinOperator import BuiltinOperator
    from tflite.BuiltinOptions import BuiltinOptions
    from tflite.ConcatenationOptions import ConcatenationOptionsT
    from tflite.OperatorCode import OperatorCodeT
    from tflite.Operator import OperatorT

    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise ValueError("Expected the pinned upstream MobileFaceNet asset")
    model = ModelT.InitFromObj(Model.GetRootAsModel(source, 0))
    if len(model.subgraphs) != 1:
        raise ValueError("Expected one subgraph")
    graph = model.subgraphs[0]
    names = {v: k for k, v in vars(BuiltinOperator).items() if type(v) is int}
    operations = Counter(names[max(model.operatorCodes[op.opcodeIndex].builtinCode,
                                   model.operatorCodes[op.opcodeIndex].deprecatedBuiltinCode)]
                         for op in graph.operators)
    expected = {"ADD": 45, "CONV_2D": 35, "RELU": 33, "ABS": 33, "SUB": 33,
                "MUL": 33, "DEPTHWISE_CONV_2D": 17, "RESHAPE": 1}
    if operations != expected or list(graph.operators[227].inputs) != [39, 35, 34]:
        raise ValueError("Unexpected graph; batch independence must be reviewed again")
    # All 227 trunk operations are per-image convolutions and elementwise operations.
    # Preserve the last two convolutions' batch=2, hence their original native kernel
    # accumulation order. Copy the 512-value trunk result instead of computing it twice.
    head_tensors = {33, 36, 367}
    activation_count = 0
    duplicate = copy.deepcopy(graph.tensors[39])
    duplicate.name = b"duplicate_trunk_for_original_head"
    duplicate_index = len(graph.tensors)
    for index, tensor in enumerate(graph.tensors):
        if model.buffers[tensor.buffer].data is None and index not in head_tensors:
            if tensor.shape[0] != 2:
                raise ValueError("Unexpected activation batch")
            tensor.shape = tensor.shape.copy()
            tensor.shape[0] = 1
            activation_count += 1
    graph.tensors.append(duplicate)
    code = len(model.operatorCodes)
    model.operatorCodes.append(OperatorCodeT(
        deprecatedBuiltinCode=BuiltinOperator.CONCATENATION,
        builtinCode=BuiltinOperator.CONCATENATION, version=1))
    concat = OperatorT(opcodeIndex=code, inputs=[39, 39], outputs=[duplicate_index],
                       builtinOptionsType=BuiltinOptions.ConcatenationOptions,
                       builtinOptions=ConcatenationOptionsT(axis=0))
    graph.operators[227].inputs = np.array([duplicate_index, 35, 34], dtype=np.int32)
    graph.operators.insert(227, concat)
    builder = flatbuffers.Builder(len(source))
    builder.Finish(model.Pack(builder), file_identifier=b"TFL3")
    output = bytes(builder.Output())
    before, after = Model.GetRootAsModel(source, 0), Model.GetRootAsModel(output, 0)
    unchanged = 0
    for index in range(before.BuffersLength()):
        a, b = before.Buffers(index), after.Buffers(index)
        if a.DataLength() != b.DataLength() or (a.DataLength()
                and bytes(a.DataAsNumpy()) != bytes(b.DataAsNumpy())):
            raise ValueError("A constant buffer changed")
        unchanged += a.DataLength() > 0
    return output, {
        "sourceSha256": SOURCE_SHA256, "derivedSha256": hashlib.sha256(output).hexdigest(),
        "bytes": len(output), "singleBatchActivationTensors": activation_count,
        "unchangedConstantBuffers": unchanged, "originalOperators": dict(operations),
        "addedOperator": "CONCATENATION(axis=0, inputs=[39,39]) before original op 227",
        "input": [1, 112, 112, 3], "output": [2, 192],
        "preservedHeadBatch": 2, "trainedWeightsChanged": False,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--flatc", required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="face-model-schema-") as generated:
        subprocess.run([args.flatc, "--python", "--gen-object-api", "-o", generated,
                        str(Path(__file__).with_name("model-schema.fbs"))], check=True, timeout=30)
        sys.path.insert(0, generated)
        output, report = derive(args.source.read_bytes())
    args.destination.parent.mkdir(parents=True, exist_ok=True)
    args.destination.write_bytes(output)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))
