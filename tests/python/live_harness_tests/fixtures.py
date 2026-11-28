"""The fixtures the tests share: XML the phone could have dumped, CSV it wrote.

The timing rows are ``tests/eta.test.sh``'s and ``tests/results.test.sh``'s,
kept verbatim so the Python suites cover the same branches. The XML is shaped
like a real uiautomator dump: one element per line, a ``package`` on every
node, resource ids under the app's package, and the card list's repeated ids.
"""

from __future__ import annotations

PACKAGE = "org.phonelink.proxy.test"
RES = "org.phonelink.proxy"

PANEL_XML = f"""<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="{PACKAGE}" content-desc="" bounds="[0,0][1080,2400]">
    <node index="0" text="Proxies" resource-id="{RES}:id/header_title" class="android.widget.TextView" package="{PACKAGE}" content-desc="list title" bounds="[24,80][300,140]" />
    <node index="1" text="New proxy" resource-id="{RES}:id/new_profile" class="android.widget.Button" package="{PACKAGE}" content-desc="new profile" bounds="[900,2200][1060,2320]" />
    <node index="2" text="" resource-id="{RES}:id/home_scroll" class="android.widget.ScrollView" package="{PACKAGE}" content-desc="" bounds="[0,160][1080,2200]" />
  </node>
</hierarchy>
"""

HEADER_ONLY_XML = f"""<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="{PACKAGE}" content-desc="" bounds="[0,0][1080,2400]">
    <node index="0" text="A proxy" resource-id="{RES}:id/header_title" class="android.widget.TextView" package="{PACKAGE}" content-desc="" bounds="[24,80][300,140]" />
  </node>
</hierarchy>
"""

CARDS_XML = f"""<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="{PACKAGE}" content-desc="" bounds="[0,0][1080,2400]">
    <node index="0" text="Proxies" resource-id="{RES}:id/header_title" class="android.widget.TextView" package="{PACKAGE}" content-desc="" bounds="[24,80][300,140]" />
    <node index="1" text="alpha" resource-id="{RES}:id/card_name" class="android.widget.TextView" package="{PACKAGE}" content-desc="" bounds="[24,200][300,260]" />
    <node index="2" text="Running" resource-id="{RES}:id/card_state" class="android.widget.TextView" package="{PACKAGE}" content-desc="" bounds="[24,260][200,320]" />
    <node index="3" text="Stop" resource-id="{RES}:id/card_toggle" class="android.widget.Button" package="{PACKAGE}" content-desc="" bounds="[800,200][1040,320]" />
    <node index="4" text="beta" resource-id="{RES}:id/card_name" class="android.widget.TextView" package="{PACKAGE}" content-desc="" bounds="[24,400][300,460]" />
    <node index="5" text="Stopped" resource-id="{RES}:id/card_state" class="android.widget.TextView" package="{PACKAGE}" content-desc="" bounds="[24,460][200,520]" />
    <node index="6" text="Start" resource-id="{RES}:id/card_toggle" class="android.widget.Button" package="{PACKAGE}" content-desc="" bounds="[800,400][1040,520]" />
  </node>
</hierarchy>
"""

QUOTE_XML = f"""<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="Proxies" resource-id="{RES}:id/header_title" class="android.widget.TextView" package="{PACKAGE}" content-desc="" bounds="[0,0][100,50]" />
  <node index="1" text="Go &quot;now&quot;" resource-id="{RES}:id/message" class="android.widget.TextView" package="{PACKAGE}" content-desc="" bounds="[0,50][100,100]" />
</hierarchy>
"""

ALERT_XML = f"""<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="Allow background?" resource-id="android:id/alertTitle" class="android.widget.TextView" package="android" content-desc="" bounds="[0,0][1080,200]" />
  <node index="1" text="Allow" resource-id="android:id/button1" class="android.widget.Button" package="android" content-desc="" bounds="[800,1600][1000,1700]" />
</hierarchy>
"""

BAD_XML = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="A toast" resource-id="" class="android.widget.TextView" package="android" content-desc="" bounds="[0,0][200,100]" />
</hierarchy>
"""

NO_HIERARCHY = "<html><body>not a dump</body></html>\n"

DUMPSYS_PACKAGE = """Packages:
  Package [org.phonelink.proxy.test] (abc):
    userId=10123
    versionName=0.17.0
    versionCode=1700
    firstInstallTime=2026-11-01 10:00:00
"""

WIFI_IP = """2: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc noqueue state UP
    link/ether aa:bb:cc:dd:ee:ff brd ff:ff:ff:ff:ff:ff
    inet 192.168.77.232/24 brd 192.168.77.255 scope global wlan0
       valid_lft forever preferred_lft forever
"""

WIFI_STATUS = """Wifi is enabled
Wifi is connected to "PhoneLink"
"""

# tests/eta.test.sh: three runs of two steps, plus a partial run.
ETA_TIMINGS = """run,step,seconds,passed,failed,status
r1,panel,10.0,7,0,ok
r1,rename,4.0,2,0,ok
r1,TOTAL,100.0,9,0,ok
r2,panel,12.0,7,0,ok
r2,rename,6.0,2,0,ok
r2,TOTAL,110.0,9,0,ok
r3,panel,14.0,7,0,ok
r3,rename,8.0,2,0,ok
r3,TOTAL,120.0,9,0,ok
r4,PARTIAL,3.0,1,0,partial
"""

# tests/eta.test.sh: the full file before filtered runs had one of their own.
ETA_MIXED = ETA_TIMINGS + """r5,panel,30.0,7,0,ok
r5,rename,20.0,2,0,ok
r5,PARTIAL,3.0,1,0,partial
"""

# tests/results.test.sh: a full run and a filtered one.
RESULTS_TIMINGS = """run,step,seconds,passed,failed,status
20261116T000000Z,unlock,3.0,1,0,ok
20261116T000000Z,panel,12.5,3,0,ok
20261116T000000Z,TOTAL,180.0,7,0,ok
"""

RESULTS_PARTIAL_TIMINGS = """run,step,seconds,passed,failed,status
20261116T010000Z,unlock,4.0,1,0,ok
20261116T010000Z,preamble,5.5,0,0,ok
20261116T010000Z,stop,15.0,4,0,ok
20261116T010000Z,PARTIAL,24.5,5,0,partial
"""

# tests/results.test.sh: a check whose claim has quotes in it.
RESULTS_CHECKS = '''run,step,verdict,file,line,function,decided_ms,claim
20261116T000000Z,unlock,pass,tests/live/proxy-app.sh,790,step_unlock,1794795267014,"the phone is unlocked"
20261116T000000Z,panel,fail,tests/live/proxy-app.sh,851,step_panel,1794795270195,"the panel opens, ""really"""
'''

# Two bursts of two edges each, 4 s apart, which is the shortest a run's two
# calibrations can be. The wall-clock gap is what separates them.
CLOCK_EDGES = """device_ns,wall_ms,on|off
1000000000,0,on
1200000000,200,off
5000000000,4000,on
5200000000,4200,off
"""
