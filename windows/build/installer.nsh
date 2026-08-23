; Discovery uses UDP/32401 in both directions. Without these rules Windows
; Firewall silently drops the tablet's announcements and the app never finds it.
!macro customInstall
  nsExec::Exec 'netsh advfirewall firewall delete rule name="Perspective USB Bridge (Discovery In)"'
  nsExec::Exec 'netsh advfirewall firewall delete rule name="Perspective USB Bridge (Discovery Out)"'
  nsExec::Exec 'netsh advfirewall firewall add rule name="Perspective USB Bridge (Discovery In)" dir=in action=allow protocol=UDP localport=32401 profile=private,domain'
  nsExec::Exec 'netsh advfirewall firewall add rule name="Perspective USB Bridge (Discovery Out)" dir=out action=allow protocol=UDP remoteport=32401 profile=private,domain'
!macroend

!macro customUnInstall
  nsExec::Exec 'netsh advfirewall firewall delete rule name="Perspective USB Bridge (Discovery In)"'
  nsExec::Exec 'netsh advfirewall firewall delete rule name="Perspective USB Bridge (Discovery Out)"'
!macroend
