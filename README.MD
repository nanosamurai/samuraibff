## nREPL server
To run nREPL server: `clojure -M:nrepl`

### nREPL MCP Server
To run the clojure repl MCP server:
`clojure -X:mcp :not-cwd true :port 7888`

### gRPC proto files 
to trigger generation and compilation of proto files:
`clj -T:build proto+compile`

