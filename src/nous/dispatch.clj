; SPDX-License-Identifier: EPL-2.0
(ns nous.dispatch
  "Per-binding hardware dispatch: scale a value against a binding and emit the
  corresponding nomos-rt IPC frame(s).

  This is the shared scale-and-emit core, extracted from nous.ctrl/send-at! so
  the same logic drives both the legacy ctrl/send! path and the ctrl-tree
  nomos-rt IPC mount (nous.ipc-mount). A binding is a plain map (see
  nous.ctrl/bind!); this namespace has no dependency on the store, so it works
  from either.

  Callers own the connection gate and the 'unknown binding type' handling —
  dispatch-binding! assumes kairos is connected and no-ops on types it does not
  emit. Emission goes through nous.kairos (semantic EDN frames); nomos-rt does
  the MIDI wire bytes."
  (:require [nous.kairos :as kairos]))

(defn dispatch-binding!
  "Scale `value` per `binding` and emit the nomos-rt frame(s).

  Handles:
    :midi-cc   — scale (value − lo)/(hi − lo)·127, clamp [0,127], send one CC.
    :midi-nrpn — clamp to the :bits range (or :raw), then send CC 99/98/6/38
                 (parameter MSB/LSB, data MSB/LSB — full 14-bit wire encoding).

  Other binding types are a no-op here; the caller logs/skips them.
  Assumes the transport is connected — the caller gates on kairos/connected?."
  [binding value]
  (case (:type binding)
    :midi-cc
    (let [ch      (int (or (:channel binding) 1))
          cc-num  (int (:cc-num binding))
          [lo hi] (or (:range binding) [0 127])
          lo      (double lo)
          hi      (double hi)
          raw     (double value)
          pct     (/ (- raw lo) (- hi lo))
          scaled  (long (Math/round (* pct 127.0)))]
      (kairos/send-cc! ch cc-num (max 0 (min 127 scaled))))

    :midi-nrpn
    (let [ch        (int (or (:channel binding) 1))
          nrpn      (int (:nrpn binding))
          bits      (int (or (:bits binding) 14))
          max-val   (if (= 14 bits) 16383 127)
          ;; :raw true → bypass range scaling; use value directly
          clamped   (if (:raw binding)
                      (max 0 (min max-val (long value)))
                      (let [[lo hi] (or (:range binding) [0 max-val])
                            pct     (/ (- (double value) (double lo))
                                       (- (double hi)   (double lo)))]
                        (max 0 (min max-val (long (Math/round (* pct (double max-val))))))))
          param-msb (bit-and (bit-shift-right nrpn 7) 0x7F)
          param-lsb (bit-and nrpn 0x7F)
          ;; Full 14-bit NRPN wire encoding: CC6=MSB, CC38=LSB.
          ;; Wire value = CC6*128 + CC38; :bits only controls value clamping range.
          data-msb  (bit-and (bit-shift-right clamped 7) 0x7F)
          data-lsb  (bit-and clamped 0x7F)]
      ;; kairos IPC preserves insertion order; no ns-offset needed.
      (kairos/send-cc! ch 99 param-msb)
      (kairos/send-cc! ch 98 param-lsb)
      (kairos/send-cc! ch  6 data-msb)
      (kairos/send-cc! ch 38 data-lsb))

    nil))
