import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
jdk = Path(os.environ['JAVA_HOME'])
src = root / 'src/org/nfcresearch/qpolllab'
(root / 'build').mkdir(exist_ok=True)
out = Path(tempfile.mkdtemp(prefix='protocol-tests-', dir=root / 'build'))
subprocess.run([str(jdk / 'bin/javac'), '-encoding', 'UTF-8', '--release', '8', '-d', str(out),
    str(src / 'core/PanelProtocol.java'),
    str(src / 'core/PanelType.java'),
    str(src / 'core/ConnectionRetry.java'),
    str(src / 'core/SessionFailure.java'),
    str(src / 'core/NciDiscovery.java'),
    str(src / 'image/ImageBlocks.java'),
    str(src / 'image/ImageRaster.java'),
    str(src / 'image/BleRaster.java'),
    str(src / 'image/CropWindow.java'),
    str(src / 'calendar/CalendarEvent.java'),
    str(src / 'calendar/CalendarMonth.java'),
    str(src / 'calendar/HolidayTable.java'),
    str(root / 'tests/ConnectionRetryTest.java'),
    str(root / 'tests/SessionFailureTest.java'),
    str(root / 'tests/NciDiscoveryTest.java'),
    str(root / 'tests/ProtocolTest.java'),
    str(root / 'tests/PanelTypeTest.java'),
    str(root / 'tests/BleRasterTest.java'),
    str(root / 'tests/ImageBlocksTest.java'),
    str(root / 'tests/ImageRasterTest.java'),
    str(root / 'tests/CropWindowTest.java'),
    str(root / 'tests/CalendarMonthTest.java'),
    str(root / 'tests/HolidayTableTest.java')], check=True)
subprocess.run([str(jdk / 'bin/java'), '-cp', str(out), 'org.nfcresearch.qpolllab.ProtocolTest', str(out)], check=True)
subprocess.run([str(jdk / 'bin/java'), '-cp', str(out), 'org.nfcresearch.qpolllab.PanelTypeTest', str(out)], check=True)
subprocess.run([str(jdk / 'bin/java'), '-cp', str(out), 'org.nfcresearch.qpolllab.BleRasterTest'], check=True)
subprocess.run([str(jdk / 'bin/java'), '-cp', str(out), 'org.nfcresearch.qpolllab.ImageBlocksTest'], check=True)
subprocess.run([str(jdk / 'bin/java'), '-cp', str(out), 'org.nfcresearch.qpolllab.ImageRasterTest', str(out)], check=True)
subprocess.run([str(jdk / 'bin/java'), '-cp', str(out), 'org.nfcresearch.qpolllab.CropWindowTest'], check=True)
for test in ('ConnectionRetryTest', 'SessionFailureTest', 'NciDiscoveryTest', 'CalendarMonthTest', 'HolidayTableTest'):
    subprocess.run([str(jdk / 'bin/java'), '-cp', str(out), 'org.nfcresearch.qpolllab.' + test], check=True)
(root / 'build/last-test-dir.txt').write_text(out.name, encoding='utf-8')
print('FIXTURES:', out)
