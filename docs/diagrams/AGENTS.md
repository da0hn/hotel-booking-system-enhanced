# AGENTS.md — docs/diagrams

Instruções para quem cria ou regera diagramas. Complementam o `AGENTS.md` da raiz.

Os diagramas do README ficam em `docs/diagrams/`. O `.excalidraw` é a fonte; o `.jpg`
é derivado e precisa ser regerado sempre que a fonte mudar:

```bash
# 1. .excalidraw -> .png (renderer headless da skill excalidraw-diagram)
cd ~/.claude/skills/excalidraw-diagram/references
uv run python render_excalidraw.py <abs-path>/docs/diagrams/NN-nome.excalidraw

# 2. .png -> .jpg reamostrado para 2600px de largura, e descarte do .png
cd <repo>/docs/diagrams
uv run --with pillow python -c "
from PIL import Image; import glob, os
for p in glob.glob('*.png'):
    im = Image.open(p); rgb = Image.new('RGB', im.size, (255,255,255))
    rgb.paste(im, mask=im.split()[-1] if im.mode=='RGBA' else None)
    if rgb.width > 2600: rgb = rgb.resize((2600, round(rgb.height*2600/rgb.width)), Image.LANCZOS)
    rgb.save(p[:-4]+'.jpg', 'JPEG', quality=90, optimize=True, subsampling=0); os.remove(p)
"
```

Só o `.jpg` é versionado — o `.png` é intermediário.

O `05-data-model` existe em duas versões, `-antes-mysql` e `-depois-postgres`, e a
primeira é **deliberada**: ela registra o desenho de três bancos separados que a migração
desfez. Não a regenere, não a "atualize" e não a apague — o que ela documenta é justamente
o estado que não existe mais. Quando uma mudança estrutural merecer o mesmo tratamento, o
par segue esse nome; nos outros casos o diagrama é editado no lugar.

O `07-mensagem-venenosa-antes-depois.gif` é a outra exceção: não tem `.excalidraw`, é a
gravação do simulador do artefato "Mensagem Venenosa" (um listener que falha, antes e depois
da #6, em três cenários) e o próprio `.gif` é o que se versiona. As esperas aparecem exatas
(1 s e 2 s, sem o jitter) e a DLQ usa os nomes atuais das filas. Ele envelhece sem acusar
quando o desenho do retry ou o nome das filas mudar: regrave-o ou apague-o na mesma mudança.
