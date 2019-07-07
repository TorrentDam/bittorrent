BitTorrent Client
=================

Development
-----------

_Requires [Mill](http://www.lihaoyi.com/mill/) build tool._

Generate idea project:
```sh
$ mill mill.scalalib.GenIdea/idea
```

Run tests:
```sh
$ mill _.test
```

Client requires [npm](https://www.npmjs.com/)
```sh
$ mill client.fastOpt
$ cd client
$ npm install
$ npm start
```
Then open http://localhost:8080

Usage
-----

```sh
$ mill cli.run --help
```