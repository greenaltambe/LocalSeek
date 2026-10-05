"""Print inputs/outputs, dtypes, shapes and an op summary of the app's cross-encoder TFLite model."""
import collections
import hashlib
import sys

import tensorflow as tf

PATH = sys.argv[1] if len(sys.argv) > 1 else "../../app/src/main/assets/models/cross_encoder.tflite"

print("sha256", hashlib.sha256(open(PATH, "rb").read()).hexdigest())
interp = tf.lite.Interpreter(model_path=PATH)
interp.allocate_tensors()
for kind, details in (("input", interp.get_input_details()), ("output", interp.get_output_details())):
    for d in details:
        print(kind, d["index"], d["name"], d["shape"].tolist(), d["dtype"].__name__)

ops = collections.Counter(op["op_name"] for op in interp._get_ops_details())
print("ops", dict(ops.most_common()))
print("tensor_count", len(interp.get_tensor_details()))
