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
$ ./mill client.fastOpt
$ cd client
$ npm install
$ npm run dev-server
```
Then open http://localhost:8080

Usage
-----

```sh
$ ./mill cli.run --help
```