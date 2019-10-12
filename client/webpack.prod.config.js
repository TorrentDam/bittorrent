module.exports = {
  mode: "production",
  entry: [
    __dirname + '/src/index.js'
  ],
  resolve: {
    extensions: ['*', '.js'],
    modules: [
      __dirname + '/node_modules',
      __dirname + '/../out/client/fullOpt/dest'
    ]
  }
};
