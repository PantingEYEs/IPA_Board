#!/usr/bin/env python3
"""Reproduce E5's exact XLM-R SentencePiece graph and fixed centering vectors.

Run in an isolated environment with onnx==1.17.0, onnxruntime==1.23.2,
onnxruntime-extensions==0.13.0, transformers==4.45.2, sentencepiece==0.2.0.
Arguments: upstream sentencepiece.bpe.model, upstream quantized ONNX, output folder.
No download or conversion runs during Gradle builds. Review/update the lockfile after regeneration.
"""
import json
import pathlib
import sys

import numpy as np
import onnx
import onnxruntime as ort
from onnxruntime_extensions import gen_processing_models, get_library_path
from transformers import XLMRobertaTokenizer

vocab, weights, destination = map(pathlib.Path, sys.argv[1:])
destination.mkdir(parents=True, exist_ok=True)
tokenizer = XLMRobertaTokenizer(vocab_file=str(vocab))
graph = gen_processing_models(tokenizer, pre_kwargs={})[0]
onnx.save(graph, destination / 'tokenizer.onnx')
options = ort.SessionOptions()
options.register_custom_ops_library(get_library_path())
session = ort.InferenceSession(str(destination / 'tokenizer.onnx'), options)
for text in ['query: 我要吃苹果', 'query: apple pie', 'query: 林檎', '☺ café', 'query: \u3000全角Ａ']:
    actual = session.run(None, {'inputs': np.array([text])})[0].tolist()
    assert actual == tokenizer(text)['input_ids'], text

# Translation-aligned, broad neutral vocabulary; independent of users or editable dictionaries.
references = {
    'EN': ['people','time','place','book','city','work','life','thing','school','water','food','music',
           'money','road','country','family','nature','weather','science','sport','art','computer','news','friend'],
    'ZH': ['人','时间','地方','书','城市','工作','生活','东西','学校','水','食物','音乐',
           '钱','路','国家','家庭','自然','天气','科学','运动','艺术','电脑','新闻','朋友'],
    'JA': ['人','時間','場所','本','都市','仕事','生活','物','学校','水','食べ物','音楽',
           'お金','道','国','家族','自然','天気','科学','スポーツ','芸術','コンピュータ','ニュース','友達'],
}
model_options = ort.SessionOptions()
model_options.intra_op_num_threads = 2
model = ort.InferenceSession(str(weights), model_options)
def embedding(text):
    ids = tokenizer('query: ' + text, truncation=True, max_length=64, return_tensors='np')['input_ids']
    hidden = model.run(None, {'input_ids': ids, 'attention_mask': np.ones_like(ids), 'token_type_ids': np.zeros_like(ids)})[0]
    pooled = hidden.mean(1)[0]
    return pooled / np.linalg.norm(pooled)
centers = {language: np.mean([embedding(text) for text in texts], axis=0).tolist() for language, texts in references.items()}
(destination / 'centering.json').write_text(json.dumps(centers, indent=2) + '\n')
print('Exact SentencePiece graph and centering vectors generated.')
