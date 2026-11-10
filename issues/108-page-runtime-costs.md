# 108: half of a hot render is runtime methods the compiled code calls; six named costs are 18% of the render

**State:** Open. Found 2026-11-10 in [wiki/ceiling.md](../wiki/ceiling.md) ("The page: where a render's time
is"). Nothing here is fixed yet; every share below is of the PHP thread's execution samples in a JFR recording of
the front page (16,000 renders in one context, the best case's options, 168 s recorded after the compile queue
drained: `tests/gate2/k8-jfr.sh`, `tests/gate2/JfrSplit.java`).

**What happens:** 47.9% of a hot render is the PHP functions' own compiled code and 49.8% is runtime methods that
code calls: built-in functions (26.5%) and the engine's own slow paths (23.3%). At 18.1 ms a render that is
8.7 ms and 9.0 ms; PHP renders the whole page in 9.75 ms. No method of the runtime half is above 5.3%, no built-in
above 3.5%.

**The named costs:**

| what | share, inclusive | the defect | a fix |
|---|---:|---|---|
| `SlotCacheNode.find` | 5.3% | an element lookup by a string key at a site without a rich form, or whose key is not at its home slot: a call with its frame, the key's hash through `TruffleString`'s node, the walk | a cheaper call (the hash passed in, no boxing of the result), or a second inline step where a site keeps missing ([107](107-machine-code-per-site.md) bounds how much may be inline) |
| `array_intersect` 2.2%, `array_diff` 0.3% | 2.5% | `ArrayFunctions.diffOrIntersect` builds a `java.util.HashSet<TruffleString>` of every other array's values at each call (`HashMap.resize` alone is 1.1% of the render) and asks a stream per element | a scan when the other array is small (WordPress's are a handful of names), one reused open-addressing set otherwise |
| `clone` | 3.5% | `RuntimeFunctions.cloneObject` makes an empty object of the class's root shape and defines the properties one by one through the generic `DynamicObject` path, a shape transition looked up per property | copy the object's storage with its shape in one step, then count the holders of the arrays and objects it holds |
| `json_encode` | 2.9% | `JsonFunctions.Encoder.string` (1.6% as the frame on top) writes a byte at a time, each with the buffer's capacity check | find the run of bytes that need no escape and copy it whole |
| a callable's resolution | 1.9% | [106](106-megamorphic-callable-dispatch.md) | |
| `md5` | 1.8% | `EncodingFunctions.digest` asks `MessageDigest.getInstance` per call (0.2%); the digest itself is the rest | one digest per context, reset per call; the rest is the JDK's intrinsic |

Together 17.9% of the render. A fix returns a part of its row, not the row: by the shares 7–11% of the render,
to be measured on the page (`tests/gate2/k8-cli.sh one NAME 8000 /`, three runs a side, with `memory.peak`).

**The rest of the runtime half,** for whoever looks next (inclusive shares):

- built-ins: `array_diff_key` 1.4% (`diffKeys` already probes the other arrays' own index; no defect named),
  `serialize` 1.2%, `ksort` 1.2%, `preg_match` 1.1%, `preg_replace` 1.1%, `sprintf` 0.7% (2.24× PHP
  as a kernel), `preg_split` 0.6%, `usort` 0.6%, `implode` 0.5%, `preg_match_all` 0.5%, `array_merge` 0.4%
  (2.06× PHP as a kernel), `json_decode` 0.4%, `str_replace` 0.4%; about fifty more under 0.4% each;
- writes to arrays: `Variables.separated` 1.5% (the copy of a shared array), `SlotCacheNode.put` 1.5%,
  `PhpArray.putSlow` 0.4%;
- `Str.join` 1.7% (a string built from parts; `interpolate` is 1.33× PHP as a kernel);
- `Refcount.zero` 1.4% and `PhpArray.lastHolderGone` 0.4% (the release of a value's members: php-src does the
  same work);
- `ArrayFunctions.search` 1.2% (`in_array`, `array_search`: 1.44× PHP as a kernel);
- lookups by name on a hot path: `PhpArray.hashGet` 0.6%, `PhpClass.methodFrom` 0.5%,
  `PhpObject.visibleProperties` 0.5%, `PropNode.readIfSetSlow` 0.4%, `PhpContext.findStaticVariable` 0.4%,
  `PropNode.dynamicName` 0.3% ([099](099-object-vars-and-dynamic-property-writes.md));
- `DynamicObject`'s `RemovePlan.performBoundary` 0.5% (`unset` of a property).

**In the compiled half** two things are the engine's own and not the page's: counting an array's holders
(`PhpArray.addHolder` 4.2%, `countIn` 1.7%, `removeHolder` 0.9% as the frame on top; php-src does it with one
increment) and the inline element lookup (`slotAtHome` 3.4%, `SlotCacheNode.lookup` 3.0%).

**Looked at and not a cost:** exceptions. A render throws about 2.3 `NoSuchFileException`s with their stack
traces (`file_exists`, `is_file` and the other file tests of a missing file, inside the JDK) and 2.0 stackless
`JsonError`s (`json_decode` of text that is not JSON); the file tests are under 0.1% of the samples and
`json_decode` 0.4% in all.

**Not known:** where PHP spends its own 9.75 ms (the image's php binary is stripped), so none of these shares has
PHP's number beside it. A share here is a cost, not a gap.
