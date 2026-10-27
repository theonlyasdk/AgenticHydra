"""Package source only: no credentials, local SDK paths, caches, or signing keys."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
out = root / 'dist'
out.mkdir(exist_ok=True)
excluded = {'build', '.gradle', '.kotlin', '.git', '.idea', 'dist'}
with ZipFile(out / 'Agentic-Hydra-project.zip', 'w', ZIP_DEFLATED) as archive:
    for path in sorted(root.rglob('*')):
        relative = path.relative_to(root)
        if not path.is_file() or excluded.intersection(relative.parts):
            continue
        if path.name in {'local.properties', '.android-env.md', '.android-signing.xml'} or path.suffix in {'.keystore', '.jks'}:
            continue
        archive.write(path, Path('AgenticHydra') / relative)
print('Source ZIP created.')
