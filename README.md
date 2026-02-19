CPA-sync inneholder kun 2 rutiner som skal kjøres skedulert:
- Synchronize CPAs
<BR>Synkroniserer innholdet i CPA-repo med CPA-ene som ligger på SFTP-serveren (master)
- Activate CPAs
<BR>Aktiverer CPA-er som ligger "på vent" på SFTP-serveren

CPA-repo aksesseres gjennom API, konfigurert i HttpClient.

SFTP-serveren er konfigurert i NFSConnector.

Skedulerte rutiner er konfigurert i App.kt

Det er mulig å manuelt kjøre rutinene gjennom endepunkter definer i Routes.kt