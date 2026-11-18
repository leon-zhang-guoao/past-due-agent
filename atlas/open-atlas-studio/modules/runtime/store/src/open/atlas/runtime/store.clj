(ns open.atlas.runtime.store
  "The shared persistence floor of one runtime instance (owner ruling
   2026-08-25, ③-1). Cross-module callers may require only this ns;
   `open.atlas.runtime.store.impl.*` is private.

   An instance **is** a root directory, and that root has exactly **one** lease.
   The graph lifecycle (`runtime/graph`) and the token ledger (`runtime/token`)
   both write under it, so the lease, the poisoned flag, the atomic landing
   primitives and the lock registry are one implementation shared by both — not
   one each:

   - two opens of `control.lock` inside one JVM do not refuse each other the way
     two processes do, so \"one writer per instance\" would stop being tested at
     the moment it matters;
   - two atomic-landing implementations means D4.12's refusal is judged twice,
     and a machine that quietly lost atomicity in one of them looks fine;
   - two poisoned flags means one subsystem keeps writing while the other is
     known to be half-applied.

   This module knows nothing about graphs, tokens, artifacts or envelopes. It
   knows this machine's disk.

   ## Layout

   Each subsystem owns a directory under the root and lays it out itself; the
   only name this module reserves is `control.lock`."
  (:require [open.atlas.runtime.store.impl.finding :as finding]
            [open.atlas.runtime.store.impl.fs :as fs]
            [open.atlas.runtime.store.impl.lease :as lease])
  (:import [java.io File]))

;; --- 租约 -------------------------------------------------------------------

(def open!
  "`{:instance-id … :root …}` → `{:instance i}` or `{:findings […]}`.
   Taken **once per process** and handed to every subsystem."
  lease/open!)

(def close!       lease/close!)
(def lease-held?  lease/held?)
(def lock-file-name lease/lock-file-name)

(defn instance-id [inst] (:instance-id inst))
(defn root ^File [inst] (:root inst))

(defn subsystem-dir
  "`<root>/<name>` — a subsystem's own corner of the instance root."
  ^File [inst name*]
  (File. (root inst) ^String (fs/safe-segment! name* "subsystem")))

;; --- 写闸 -------------------------------------------------------------------

(def writable-findings
  "The two gates before any write: lease still valid, process not poisoned.
   Empty = go ahead."
  lease/writable-findings)

(def poison!       lease/poison!)
(def poisoned      lease/poisoned)
(def clear-poison! lease/clear-poison!)

;; --- 子系统串行 --------------------------------------------------------------

(def monitor lease/monitor)

(defmacro with-lock
  "Serialise on one key inside this process; different keys run in parallel.
   **The lock guards concurrent writes, a CAS guards stale requests — they are
   not substitutes** (owner ruling 2026-08-24). Namespace the key by subsystem."
  [inst key & body]
  `(locking (monitor ~inst ~key) ~@body))

;; --- 原子落盘 ---------------------------------------------------------------

(def write-edn!   fs/write-edn!)
(def write-dir!   fs/write-dir!)
(def read-edn     fs/read-edn)
(def sweep-temps! fs/sweep-temps!)
(def atomic-move! fs/atomic-move!)
(def safe-segment! fs/safe-segment!)
(def finding-of
  "The structured refusal carried by an exception thrown from here, if any."
  fs/finding-of)

;; --- finding ----------------------------------------------------------------

(def finding-codes finding/codes)
(defn finding? [x] (finding/valid? x))
