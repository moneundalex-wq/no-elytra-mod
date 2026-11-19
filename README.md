# No Elytra on Armor Stands

Client-seitige Fabric-Mod für Minecraft **1.21.11**. Sie verhindert, dass du eine Elytra
(in Haupt- oder Nebenhand) auf einen Armor Stand legst.

- Nur Client, muss nicht auf dem Server installiert sein
- Keine Mixins, nur das `UseEntityCallback` der Fabric API, daher kein Konflikt mit Sodium & Co.
- Benötigt: Fabric Loader >= 0.18.0, Fabric API, Java 21

## Jar ohne Installation bauen (empfohlen): GitHub

1. Auf https://github.com ein kostenloses Konto anlegen und ein neues Repository erstellen.
2. Den **Inhalt** dieses Ordners hochladen (Add file -> Upload files). Wichtig: auch der
   versteckte Ordner `.github` muss dabei sein. Falls er beim Ziehen nicht mitkommt, die Datei
   `.github/workflows/build.yml` über "Create new file" mit genau diesem Pfad anlegen.
3. Oben auf den Tab **Actions** gehen. Der Build startet automatisch (ca. 3-5 Minuten).
4. Wenn er grün ist: auf den Lauf klicken, unten bei **Artifacts** `no-elytra-on-armor-stand`
   herunterladen, ZIP entpacken. Die `.jar` darin kommt in deinen `mods`-Ordner
   (zusammen mit Fabric API).

## Lokal bauen

Voraussetzung: JDK 21 und Internet.

```
gradle build
```

(Oder mit einem Gradle-Wrapper aus einer Vorlage von https://fabricmc.net/develop/template/.)
Die Mod liegt danach in `build/libs/` (die Datei ohne `-sources`).
Zum Testen: `gradle runClient`.

## Versionen anpassen

Alle Versionen stehen in `gradle.properties`. Empfohlene Werte findest du auf
https://fabricmc.net/develop.
