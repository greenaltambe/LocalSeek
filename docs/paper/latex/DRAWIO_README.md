# Diagrams: Mermaid code for draw.io

The paper has five schematic diagrams. Each one exists in three forms, all showing the same content:

| File | What it is | Used for |
|---|---|---|
| `diagrams/<name>.mmd` | Mermaid code | paste into draw.io, or edit and re-render |
| `drawio/<name>.pdf` | the rendered diagram | what the paper includes (`\schematic{<name>}`) |
| `drawio/<name>.drawio` | native draw.io file | open directly in draw.io |
| `tikz/<name>.tex` | TikZ version | fallback if `drawio/<name>.pdf` is missing |

Diagrams: `studies` (Figure 1, the three studies), `arch` (Figure 2, architecture), `protocol` (Figure 3, Study 1A protocol), `userstudy` (Study 1B flow), `replication` (Study 3 workflow).

## Open a diagram in draw.io (app.diagrams.net or the desktop app)

1. Open `diagrams/<name>.mmd` in a text editor and copy all of it.
2. In draw.io: **Arrange > Insert > Advanced > Mermaid** (in some versions **Extras > Edit Diagram** does not work for Mermaid; use Insert).
3. Paste, click **Insert**. The diagram appears as an editable shape. Choose "Diagram" instead of "Image" in the dialog if you want to move single boxes.
4. Or simply open `drawio/<name>.drawio` with File > Open.

## Put an edited diagram back into the paper

1. In draw.io: **File > Export as > PDF**, tick **Crop**, then Export.
2. Save it as `docs/paper/latex/drawio/<name>.pdf` (same name, overwrite).
3. Rebuild: `cd docs/paper/latex && latexmk -pdf main.tex`.

## Re-render from the Mermaid code (no draw.io needed)

```
npm install -g @mermaid-js/mermaid-cli
cd docs/paper/latex
for f in diagrams/*.mmd; do n=$(basename $f .mmd); mmdc -i $f -o drawio/$n.pdf -c mermaid-config.json --pdfFit -b white; done
```
