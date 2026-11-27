import QtQuick
import Quickshell
import Quickshell.Io
import qs.Common
import qs.DCommon.Widgets
import qs.Modules.Plugins

// Bar widget for desk_sidecar (the desk phone dashboard). Talks to the phone through the `phonedash` CLI.
PluginComponent {
    id: root

    layerNamespacePlugin: "phone-dash"
    popoutWidth: 420

    readonly property string cli: Quickshell.env("HOME") + "/.local/bin/phonedash"
    property var status: null
    readonly property bool reachable: status !== null
    readonly property bool screenOff: reachable && status.screen_off === true
    readonly property string pillText: !reachable ? "—" : (screenOff ? "off" : status.brightness + "%")

    // Schedule editor state: {brightness:[{time,pct}], night:[{a,b}], off:[{a,b}]}
    property var sched: null
    property bool showSchedule: false
    property bool showNotes: false
    property bool showWidgets: false
    property bool showOptions: false
    property var notesList: []          // [{text}]
    property var layout: null           // {tabs:[...], all:[...], show_billable, show_notes}
    property var options: null          // {lyrics, light, on_lock}
    readonly property var tabNames: ({ notifications: "Notifications", agenda: "Agenda", home: "Home", photos: "Photos", weather: "Weather", pc: "PC", display: "Screen" })

    function openSection(name) {
        root.showSchedule = name === "schedule" && !root.showSchedule;
        root.showNotes = name === "notes" && !root.showNotes;
        root.showWidgets = name === "widgets" && !root.showWidgets;
        // Fasce orarie / Widget open inside Opzioni, which stays open
        root.showOptions = (name === "options" && !root.showOptions) || name === "schedule" || name === "widgets";
        root.saveMsg = "";
        if (root.showSchedule) root.loadSchedule();
        if (root.showNotes) { notesProc.running = true; }
        if (root.showWidgets) { layoutProc.running = true; }
        if (root.showOptions) { optsProc.running = true; }
    }

    function saveNotes() {
        const items = root.notesList.map(n => n.text.trim()).filter(t => t.length > 0);
        root.saveMsg = "Saving…";
        saveProc.command = [root.cli, "set-notes", JSON.stringify(items)];
        saveProc.running = true;
    }

    // Layout: rows for every tab, visible ones first in their order.
    function layoutRows() {
        if (!root.layout) return [];
        const vis = root.layout.tabs;
        return vis.map(k => ({ key: k, on: true })).concat(root.layout.all.filter(k => vis.indexOf(k) < 0).map(k => ({ key: k, on: false })));
    }

    function saveLayout(rows, billable, notes) {
        const d = { tabs: rows.filter(r => r.on).map(r => r.key), show_billable: billable, show_notes: notes };
        root.layout = Object.assign({}, root.layout, d);
        root.saveMsg = "Saving…";
        saveProc.command = [root.cli, "set-layout", JSON.stringify(d)];
        saveProc.running = true;
    }
    property string saveMsg: ""

    function loadSchedule() {
        root.saveMsg = "";
        if (!schedProc.running)
            schedProc.running = true;
    }

    function setSched(s) {
        root.sched = JSON.parse(JSON.stringify(s)); // new object -> Repeaters rebuild
    }

    function saveSchedule() {
        const s = root.sched;
        const out = {
            brightness: s.brightness.map(r => [r.time.trim(), Math.round(r.pct)]),
            night: s.night.map(r => r.a.trim() + "-" + r.b.trim()),
            off: s.off.map(r => r.a.trim() + "-" + r.b.trim())
        };
        root.saveMsg = "Saving…";
        saveProc.command = [root.cli, "set-schedule", JSON.stringify(out)];
        saveProc.running = true;
    }

    // phonedash.service (heartbeat, theme, photos) running on this PC
    property bool serviceActive: true
    // Player wave/cover animation mode: "off" | "30s" | "always"
    property string animMode: "30s"
    readonly property var animModes: ["off", "30s", "always"]

    function setService(on) {
        svcCmd.command = ["systemctl", "--user", on ? "start" : "stop", "phonedash.service"];
        svcCmd.running = true;
    }

    function refresh() {
        if (!svcProc.running)
            svcProc.running = true;
        if (!animProc.running)
            animProc.running = true;
        if (!statusProc.running)
            statusProc.running = true;
    }

    function run(args) {
        cmdProc.command = [root.cli].concat(args);
        cmdProc.running = true;
    }

    Process {
        id: statusProc
        command: [root.cli, "status"]
        stdout: StdioCollector {
            onStreamFinished: {
                try {
                    root.status = JSON.parse(text);
                } catch (e) {
                    root.status = null;
                }
            }
        }
        onExited: exitCode => {
            if (exitCode !== 0)
                root.status = null;
        }
    }

    Process {
        id: schedProc
        command: [root.cli, "schedule"]
        stdout: StdioCollector {
            onStreamFinished: {
                try {
                    const d = JSON.parse(text);
                    const rng = r => ({ a: r.split("-")[0], b: r.split("-")[1] });
                    root.setSched({
                        brightness: d.brightness.map(e => ({ time: e[0], pct: e[1] })),
                        night: d.night.map(rng),
                        off: d.off.map(rng)
                    });
                } catch (e) {
                    root.saveMsg = "Config not readable";
                }
            }
        }
    }

    Process {
        id: notesProc
        command: [root.cli, "notes"]
        stdout: StdioCollector {
            onStreamFinished: {
                try { root.notesList = JSON.parse(text).map(t => ({ text: t })); } catch (e) { root.notesList = []; }
            }
        }
    }

    Process {
        id: layoutProc
        command: [root.cli, "layout"]
        stdout: StdioCollector {
            onStreamFinished: {
                try { root.layout = JSON.parse(text); } catch (e) { root.layout = null; }
            }
        }
    }

    Process {
        id: optsProc
        command: [root.cli, "options"]
        stdout: StdioCollector {
            onStreamFinished: {
                try { root.options = JSON.parse(text); } catch (e) { root.options = null; }
            }
        }
    }

    Process {
        id: saveProc
        stderr: StdioCollector {
            id: saveErr
        }
        onExited: exitCode => {
            root.saveMsg = exitCode === 0 ? "Saved ✓" : (saveErr.text.trim() || "Error");
            if (exitCode === 0)
                root.loadSchedule();
            root.refresh();
        }
    }

    // Reusable editor rows ------------------------------------------------------------------
    component BrightRow: Row {
        required property var modelData
        required property int index
        spacing: Theme.spacingS
        DTextField {
            width: 76
            anchors.verticalCenter: parent.verticalCenter
            text: modelData.time
            placeholderText: "HH:MM"
            onTextChanged: root.sched.brightness[index].time = text
        }
        DSlider {
            width: 196
            anchors.verticalCenter: parent.verticalCenter
            minimum: 0
            maximum: 100
            value: modelData.pct
            unit: "%"
            onSliderValueChanged: newValue => root.sched.brightness[index].pct = newValue
        }
        DelButton {
            onClicked: {
                const s = root.sched;
                s.brightness.splice(index, 1);
                root.setSched(s);
            }
        }
    }

    component RangeRow: Row {
        required property var modelData
        required property int index
        property string key: "night"
        spacing: Theme.spacingS
        DTextField {
            width: 76
            anchors.verticalCenter: parent.verticalCenter
            text: modelData.a
            placeholderText: "HH:MM"
            onTextChanged: root.sched[key][index].a = text
        }
        StyledText {
            anchors.verticalCenter: parent.verticalCenter
            text: "→"
            color: Theme.surfaceVariantText
        }
        DTextField {
            width: 76
            anchors.verticalCenter: parent.verticalCenter
            text: modelData.b
            placeholderText: "HH:MM"
            onTextChanged: root.sched[key][index].b = text
        }
        DelButton {
            onClicked: {
                const s = root.sched;
                s[key].splice(index, 1);
                root.setSched(s);
            }
        }
    }

    component DelButton: Rectangle {
        signal clicked
        anchors.verticalCenter: parent ? parent.verticalCenter : undefined
        width: 32
        height: 32
        radius: 16
        color: delMouse.containsMouse ? Theme.surfaceContainerHighest : "transparent"
        DIcon {
            anchors.centerIn: parent
            name: "close"
            size: Theme.iconSizeSmall + 2
            color: Theme.surfaceVariantText
        }
        MouseArea {
            id: delMouse
            anchors.fill: parent
            hoverEnabled: true
            cursorShape: Qt.PointingHandCursor
            onClicked: parent.clicked()
        }
    }

    component SectionTitle: StyledText {
        font.pixelSize: Theme.fontSizeMedium
        color: Theme.surfaceVariantText
    }

    Process {
        id: svcProc
        command: ["systemctl", "--user", "is-active", "phonedash.service"]
        stdout: StdioCollector {
            onStreamFinished: root.serviceActive = text.trim() === "active"
        }
    }

    Process {
        id: animProc
        command: [root.cli, "animation"]
        stdout: StdioCollector {
            onStreamFinished: {
                const m = text.trim();
                if (root.animModes.indexOf(m) >= 0)
                    root.animMode = m;
            }
        }
    }

    Process {
        id: svcCmd
        onExited: root.refresh()
    }

    Process {
        id: cmdProc
        onExited: {
            root.refresh();
            if (root.showOptions)
                optsProc.running = true;
        }
    }

    Timer {
        interval: 30000
        running: true
        repeat: true
        triggeredOnStart: true
        onTriggered: root.refresh()
    }

    horizontalBarPill: Component {
        Row {
            spacing: Theme.spacingXS
            DIcon {
                anchors.verticalCenter: parent.verticalCenter
                name: "smartphone"
                size: Theme.iconSizeSmall + 2
                filled: root.reachable && !root.screenOff
                color: root.reachable ? Theme.primary : Theme.surfaceVariantText
            }
            StyledText {
                anchors.verticalCenter: parent.verticalCenter
                text: root.pillText
                font.pixelSize: Theme.fontSizeSmall
                color: root.reachable ? Theme.surfaceText : Theme.surfaceVariantText
            }
        }
    }

    verticalBarPill: Component {
        Column {
            spacing: 2
            DIcon {
                anchors.horizontalCenter: parent.horizontalCenter
                name: "smartphone"
                size: Theme.iconSizeSmall + 2
                filled: root.reachable && !root.screenOff
                color: root.reachable ? Theme.primary : Theme.surfaceVariantText
            }
            StyledText {
                anchors.horizontalCenter: parent.horizontalCenter
                text: root.pillText
                font.pixelSize: Theme.fontSizeSmall - 2
                color: Theme.surfaceText
            }
        }
    }

    popoutContent: Component {
        PopoutComponent {
            headerText: "Phone"
            detailsText: !root.reachable ? "Unreachable"
                : root.status.photos + " photos · Google Home " + (root.status.google_home ? "connected" : "not connected")
                  + (root.status.pc_online === false ? " · PC seen offline" : "")

            // Scrollable body: the popout never grows past the screen.
            DFlickable {
                width: parent.width
                height: Math.min(mainCol.implicitHeight, 600)
                contentWidth: width
                contentHeight: mainCol.implicitHeight
                clip: true

            Column {
                id: mainCol
                width: parent.width
                spacing: Theme.spacingM
                topPadding: Theme.spacingS

                StyledText {
                    text: "Brightness" + (root.reachable && root.status.brightness_override === null ? " · scheduled" : " · manual")
                    font.pixelSize: Theme.fontSizeMedium
                    color: Theme.surfaceVariantText
                }

                DSlider {
                    width: parent.width
                    minimum: 0
                    maximum: 100
                    value: root.reachable ? root.status.brightness : 0
                    startIcon: "brightness_low"
                    endIcon: "brightness_high"
                    unit: "%"
                    enabled: root.reachable
                    onSliderDragFinished: finalValue => root.run(["brightness", String(finalValue)])
                }

                DButton {
                    text: "Back to scheduled brightness"
                    variant: "tonal"
                    iconName: "schedule"
                    enabled: root.reachable && root.status.brightness_override !== null
                    onClicked: root.run(["brightness", "auto"])
                }

                StyledText {
                    text: "Screen"
                    font.pixelSize: Theme.fontSizeMedium
                    color: Theme.surfaceVariantText
                }

                DButtonGroup {
                    arrowKeysSelect: false
                    model: ["Auto", "On", "Off"]
                    enabled: root.reachable
                    currentIndex: !root.reachable ? -1
                        : root.status.screen_override === null ? 0
                        : (root.status.screen_override ? 1 : 2)
                    onSelectionChanged: (index, selected) => { if (selected) currentIndex = index;
                        if (selected)
                            root.run(["screen", ["auto", "on", "off"][index]]);
                    }
                }

                DButton {
                    // The PC monitor streamed to the phone ("Screen" tab), full screen on the phone or back.
                    text: root.status && root.status.mirror === "full" ? "PC screen: exit full screen" : "PC screen full screen"
                    iconName: root.status && root.status.mirror === "full" ? "fullscreen_exit" : "fullscreen"
                    variant: "tonal"
                    enabled: root.reachable
                    onClicked: root.run(["mirror", "toggle"])
                }

                Row {
                    spacing: Theme.spacingS
                    DButton { text: "Post-it"; iconName: "sticky_note_2"; variant: root.showNotes ? "filled" : "tonal"; onClicked: root.openSection("notes") }
                    DButton {
                        // Slideshow mode = phonedash service stopped: the phone sees the PC as off and shows the photo frame.
                        text: root.serviceActive ? "Slideshow" : "Slideshow on"
                        iconName: root.serviceActive ? "slideshow" : "stop_circle"
                        variant: root.serviceActive ? "tonal" : "filled"
                        onClicked: root.setService(!root.serviceActive)
                    }
                    DButton { text: "Options"; iconName: "tune"; variant: root.showOptions ? "filled" : "tonal"; onClicked: root.openSection("options") }
                }

                // ---- Post-it -------------------------------------------------------------
                Column {
                    visible: root.showNotes
                    width: parent.width
                    spacing: Theme.spacingS
                    SectionTitle { text: "Post-its on the dashboard (max 6)" }
                    Repeater {
                        model: root.notesList
                        delegate: Row {
                            required property var modelData
                            required property int index
                            spacing: Theme.spacingS
                            DTextField {
                                width: 320
                                text: modelData.text
                                placeholderText: "Note…"
                                onTextChanged: root.notesList[index].text = text
                            }
                            DelButton {
                                onClicked: {
                                    const l = root.notesList.slice();
                                    l.splice(index, 1);
                                    root.notesList = l;
                                }
                            }
                        }
                    }
                    Row {
                        spacing: Theme.spacingM
                        DButton {
                            text: "Add"
                            variant: "text"
                            iconName: "add"
                            enabled: root.notesList.length < 6
                            onClicked: root.notesList = root.notesList.concat([{ text: "" }])
                        }
                        DButton { text: "Save"; iconName: "save"; onClicked: root.saveNotes() }
                        StyledText {
                            anchors.verticalCenter: parent.verticalCenter
                            text: root.saveMsg
                            font.pixelSize: Theme.fontSizeSmall
                            color: Theme.surfaceVariantText
                        }
                    }
                }

                // ---- Widget: tab order / visibility ------------------------------------------
                Column {
                    id: widgetsCol
                    visible: root.showWidgets && root.layout !== null
                    width: parent.width
                    spacing: Theme.spacingXS
                    property var rows: root.layoutRows()
                    property bool billable: root.layout ? root.layout.show_billable : true
                    property bool notes: root.layout ? root.layout.show_notes : true
                    // Saved on every change; delegates can't see `root`, so they go through here.
                    function persist() { root.saveLayout(widgetsCol.rows, widgetsCol.billable, widgetsCol.notes); }
                    // One call from the delegates: assigning rows destroys the calling delegate, nothing may run after it.
                    function apply(r) { widgetsCol.rows = r; persist(); }
                    Connections {
                        target: root
                        function onLayoutChanged() { widgetsCol.rows = root.layoutRows(); }
                    }
                    SectionTitle { text: "Tabs (order and visibility)" }
                    Repeater {
                        model: widgetsCol.rows
                        delegate: Row {
                            required property var modelData
                            required property int index
                            spacing: Theme.spacingS
                            DButtonGroup {
                                arrowKeysSelect: false
                                model: ["Visible", "Hidden"]
                                currentIndex: modelData.on ? 0 : 1
                                onSelectionChanged: (i, sel) => { if (sel) currentIndex = i;
                                    if (!sel || modelData.on === (i === 0)) return;
                                    const r = widgetsCol.rows.slice();
                                    r[index] = { key: modelData.key, on: i === 0 };
                                    widgetsCol.apply(r);
                                }
                            }
                            StyledText {
                                anchors.verticalCenter: parent.verticalCenter
                                width: 90
                                text: root.tabNames[modelData.key]
                                color: modelData.on ? Theme.surfaceText : Theme.surfaceVariantText
                            }
                            DIconButton {
                                iconName: "arrow_upward"
                                enabled: index > 0
                                onClicked: {
                                    const r = widgetsCol.rows.slice();
                                    const x = r[index]; r[index] = r[index - 1]; r[index - 1] = x;
                                    widgetsCol.apply(r);
                                }
                            }
                            DIconButton {
                                iconName: "arrow_downward"
                                enabled: index < widgetsCol.rows.length - 1
                                onClicked: {
                                    const r = widgetsCol.rows.slice();
                                    const x = r[index]; r[index] = r[index + 1]; r[index + 1] = x;
                                    widgetsCol.apply(r);
                                }
                            }
                        }
                    }
                    SectionTitle { text: "Hours card (Billable)" }
                    DButtonGroup {
                        arrowKeysSelect: false
                        model: ["Show", "Hide"]
                        currentIndex: widgetsCol.billable ? 0 : 1
                        onSelectionChanged: (i, sel) => { if (sel) currentIndex = i; if (sel && widgetsCol.billable !== (i === 0)) { widgetsCol.billable = i === 0; widgetsCol.persist(); } }
                    }
                    SectionTitle { text: "Post-it" }
                    DButtonGroup {
                        arrowKeysSelect: false
                        model: ["Show", "Hide"]
                        currentIndex: widgetsCol.notes ? 0 : 1
                        onSelectionChanged: (i, sel) => { if (sel) currentIndex = i; if (sel && widgetsCol.notes !== (i === 0)) { widgetsCol.notes = i === 0; widgetsCol.persist(); } }
                    }
                    Row {
                        spacing: Theme.spacingM
                        StyledText {
                            anchors.verticalCenter: parent.verticalCenter
                            text: root.saveMsg
                            font.pixelSize: Theme.fontSizeSmall
                            color: Theme.surfaceVariantText
                        }
                    }
                }

                // ---- Options -----------------------------------------------------------------
                Column {
                    visible: root.showOptions && root.options !== null
                    width: parent.width
                    spacing: Theme.spacingS
                    Flow {
                        width: parent.width
                        spacing: Theme.spacingS
                        DButton { text: "Schedule"; iconName: "schedule"; variant: root.showSchedule ? "filled" : "tonal"; onClicked: root.openSection("schedule") }
                        DButton { text: "Widget"; iconName: "dashboard"; variant: root.showWidgets ? "filled" : "tonal"; onClicked: root.openSection("widgets") }
                    }
                    SectionTitle {
                        text: "Player animation (wave and cover)"
                        font.pixelSize: Theme.fontSizeMedium
                        color: Theme.surfaceVariantText
                    }

                    DButtonGroup {
                        arrowKeysSelect: false
                        model: ["Off", "30 s", "Always"]
                        currentIndex: root.animModes.indexOf(root.animMode)
                        onSelectionChanged: (index, selected) => { if (selected) currentIndex = index;
                            if (selected && root.animModes[index] !== root.animMode) {
                                root.animMode = root.animModes[index];
                                root.run(["animation", root.animModes[index]]);
                            }
                        }
                    }
                    SectionTitle { text: "Dashboard language" }
                    DButtonGroup {
                        arrowKeysSelect: false
                        model: ["Italiano", "English"]
                        currentIndex: !root.options ? -1 : (root.options.language === "en" ? 1 : 0)
                        onSelectionChanged: (i, sel) => { if (sel) currentIndex = i; if (sel) root.run(["option", "language", i === 1 ? "en" : "it"]); }
                    }
                    SectionTitle { text: "Song lyrics in the photo frame" }
                    DButtonGroup {
                        arrowKeysSelect: false
                        model: ["On", "Off"]
                        currentIndex: root.options && root.options.lyrics ? 0 : 1
                        onSelectionChanged: (i, sel) => { if (sel) currentIndex = i; if (sel) root.run(["option", "lyrics", i === 0 ? "on" : "off"]); }
                    }
                    SectionTitle { text: "Adjust brightness with the light sensor" }
                    DButtonGroup {
                        arrowKeysSelect: false
                        model: ["On", "Off"]
                        currentIndex: root.options && root.options.light ? 0 : 1
                        onSelectionChanged: (i, sel) => { if (sel) currentIndex = i; if (sel) root.run(["option", "light", i === 0 ? "on" : "off"]); }
                    }
                    SectionTitle { text: "Time per photo in the slideshow" }
                    DSlider {
                        width: parent.width
                        minimum: 5
                        maximum: 120
                        step: 5
                        unit: " s"
                        value: root.options ? root.options.slideshow : 20
                        onSliderDragFinished: finalValue => root.run(["option", "slideshow", String(finalValue)])
                    }
                    SectionTitle { text: "When the PC is locked" }
                    DButtonGroup {
                        arrowKeysSelect: false
                        model: ["Photo frame", "Black screen", "Nothing"]
                        currentIndex: !root.options ? -1 : ["frame", "off", "none"].indexOf(root.options.on_lock)
                        onSelectionChanged: (i, sel) => { if (sel) currentIndex = i; if (sel) root.run(["option", "on_lock", ["frame", "off", "none"][i]]); }
                    }
                }

                Column {
                    visible: root.showSchedule && root.sched !== null
                    width: parent.width
                    spacing: Theme.spacingS

                    SectionTitle { text: "Brightness (holds until the next slot)" }
                    Repeater {
                        model: root.sched ? root.sched.brightness : []
                        delegate: BrightRow {}
                    }
                    DButton {
                        text: "Add slot"
                        variant: "text"
                        iconName: "add"
                        onClicked: {
                            const s = root.sched;
                            s.brightness.push({ time: "12:00", pct: 40 });
                            root.setSched(s);
                        }
                    }

                    SectionTitle { text: "Night (clock only)" }
                    Repeater {
                        model: root.sched ? root.sched.night : []
                        delegate: RangeRow { key: "night" }
                    }
                    DButton {
                        text: "Add range"
                        variant: "text"
                        iconName: "add"
                        onClicked: {
                            const s = root.sched;
                            s.night.push({ a: "23:00", b: "07:00" });
                            root.setSched(s);
                        }
                    }

                    SectionTitle { text: "Screen off (black, tap = 30 s)" }
                    Repeater {
                        model: root.sched ? root.sched.off : []
                        delegate: RangeRow { key: "off" }
                    }
                    DButton {
                        text: "Add range"
                        variant: "text"
                        iconName: "add"
                        onClicked: {
                            const s = root.sched;
                            s.off.push({ a: "01:00", b: "06:30" });
                            root.setSched(s);
                        }
                    }

                    Row {
                        spacing: Theme.spacingM
                        DButton {
                            text: "Save"
                            iconName: "save"
                            onClicked: root.saveSchedule()
                        }
                        StyledText {
                            anchors.verticalCenter: parent.verticalCenter
                            text: root.saveMsg
                            color: root.saveMsg.indexOf("✓") >= 0 ? Theme.primary : Theme.surfaceVariantText
                            font.pixelSize: Theme.fontSizeSmall
                        }
                    }
                }
            }
            }
        }
    }
}
