const webpack = require('webpack');

module.exports = {
  entry: '../out/client/fullOpt/dest/out.js',
  module: {
    rules: [
      { test: /\.css$/, loader: "style-loader!css-loader" }
    ]
  },
  resolve: {
    extensions: ['*', '.js'],
    modules: [ __dirname + '/node_modules', __dirname + '/src']
  },
  output: {
    path: __dirname + '/../out/client/webpackBundle/dest',
    publicPath: '/',
    filename: 'bundle.js'
  }
};
