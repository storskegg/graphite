# Feasibility spike

Each of these decides part of the design. Prove them on the Pixel 7 Pro and the
Pixel 10 Pro XL before building on them.

| # | Question | If it fails |
|---|---|---|
| 1 | Does AOA work between the two Pixels in both host directions, and what is the sustained throughput? (Expect 30-40 MB/s; measure with a USB 3 cable too.) | Laptop route becomes the default |
| 2 | Can a phone act as an adb client over USB to the other phone in AOA accessory + adb mode? | Laptop does the one-time privileged setup |
| 3 | Does "USB controlled by" swap the host role reliably on both phones, GrapheneOS included? | Laptop does the setup for the second phone |
| 4 | Does a `pm install` from the shell lift the SMS and call-log restrictions on Android 17, and skip sideloading verification on stock? | Install both phones from the laptop with `adb install` |
| 5 | Can the target's MediaStore entries be given the source's `DATE_TAKEN` and modification time? | Accept that sort order may follow EXIF only |
