Do not start a new clojure process when debugging 
use `clj-nrepl-eval -h` to evaluate clojure code in the running repl
repl port is in deps.local.edn or 4005
only use clojure poly alias if the poly tool is not installed on the machine

no need to reset the system if this is the first run after jvm start. just use "go"

create temp files (if needed) in project directory. you should delete them after you are done

if you kill the jvm, start a new one in the background using bb repl, then initialize with "(user/go)"

use the ?<- macro to test expressions in the repl

docs on rama clojure api can be found at https://redplanetlabs.com/clojuredoc/com.rpl.rama.html