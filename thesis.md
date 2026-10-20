# Thesis

Code neighbours is a tool which takes your codebase by lsp, and for a specific
entity, returns you which symbols are next to each other, by reading the lsp
references. If file A has a reference towards File B, we want to know that File
A is a direct neighbour of File B. The same goes for namespaces and directories,
abstracized. There needs to be a config.

We want a graphvis dot renderer which turns our neighbor data into a graph, and
we want a html liveserver which has an active node, and given navigation (wasd,
hjkl, arrow keys) to navigate around the graph by walking the connecting lines
and nodes.
