# Case / stand

Parametric FreeCAD macros (Part workbench). Run them from *Macro → Macros… → Execute*, or headless:

```sh
flatpak run --command=FreeCADCmd org.freecad.FreeCAD case/a53_phone_model.FCMacro
flatpak run --command=FreeCADCmd org.freecad.FreeCAD case/a53_desk_stand.FCMacro
```

Each macro writes `.FCStd`, `.step` and `.stl` next to itself (`OUT_DIR` at the top).

| File | What |
|---|---|
| `a53_phone_model.FCMacro` → `galaxy_a53.*` | Galaxy A53 reference model, separate solids: `Body` (USB-C cut), `Screen` (active area, 0.2 mm proud), `USB_C` (port volume, usable as a cutting tool), `CameraBump` |
| `a53_desk_stand.FCMacro` → `a53_desk_stand.*` | first-draft desk stand: landscape, 18° recline, slide-in from the top, front lips on three sides, open right side for USB-C + speaker, vented back with cable pass, two ribs and a base |
| `cads.FCStd` | working design |

**Only the body size (159.6 × 74.8 × 8.1 mm) is Samsung's spec.** The screen comes from the 6.5"
20:9 diagonal; bezels, port, punch-hole and camera positions are estimates — measure your phone
and edit the parameters at the top of the macros.

Print in PETG or ASA: the phone is always charging and gets warm.
