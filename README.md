BitTorrent Client
=================

Development
-----------

Generate idea project:
```sh
$ ./mill mill.scalalib.GenIdea/idea
```

Run tests:
```sh
$ ./mill _.test
```

Client requires [npm](https://www.npmjs.com/)
```sh
$ ./mill -w client.compileJs
```
Run in another terminal
```sh
$ cd client
$ npm install
$ npm run dev-server
```
Then open http://localhost:8080. Changes will refresh page automatically.

Usage
-----

```sh
$ ./mill cli.run --help
```