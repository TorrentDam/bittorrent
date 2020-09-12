BitTorrent Client
=================

Development
-----------

Generate idea project:
```sh
$ ./mill mill.scalalib.GenIdea/idea
```
You can open it as BSP projet as well:
```sh
$ ./mill mill.contrib.BSP/install
```

Run tests:
```sh
$ ./mill _.test
```

Client requires [npm](https://www.npmjs.com/)
```sh
$ ./mill -w webapp.compileJs
```
Run in another terminal
```sh
$ cd webapp
$ npm install
$ npm start
```
Then open http://localhost:8080. Changes will refresh page automatically.

Usage
-----

```sh
$ ./mill cli.run --help
```
