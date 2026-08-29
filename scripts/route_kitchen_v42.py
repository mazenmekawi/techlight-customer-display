from pathlib import Path

login = Path('app/src/main/java/sa/techlight/customerdisplay/KitchenLoginActivityV3.java')
text = login.read_text(encoding='utf-8')
text = text.replace('KitchenActivityV3.class', 'KitchenActivityV42.class')
if 'KitchenActivityV42.class' not in text:
    raise SystemExit('Kitchen V4.2 login route was not applied')
login.write_text(text, encoding='utf-8')

manifest = Path('app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
if 'android:name=".KitchenActivityV42"' not in manifest:
    raise SystemExit('Kitchen V4.2 is not the launcher activity')

activity = Path('app/src/main/java/sa/techlight/customerdisplay/KitchenActivityV42.java').read_text(encoding='utf-8')
for required in ['StrictInvoiceExtractor.extract(raw)', 'order.id = "invoice-" + number', 'handler.postDelayed(this, 1000L)', 't("readyAction")']:
    if required not in activity:
        raise SystemExit(f'Missing Kitchen V4.2 invariant: {required}')

print('Kitchen V4.2 routing and invariants verified')
