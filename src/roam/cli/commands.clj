(ns roam.cli.commands
  "CLI surface — user/agent commands, output formatting."
  (:require [roam.core.read :as read]
            [roam.core.write :as write]
            [roam.core.search :as search]
            [roam.core.hierarchy :as hierarchy]))

;; CLI layer will grow as commands are fleshed out.
;; For now, bb.edn wires directly to core layer CLI wrappers.
;; This ns is the future home of: context, daily, write --tree, etc.
