const webpack = require('webpack');

module.exports = {
  entry: ['index.js'],
  module: {
    rules: [
      { test: /\.js$/, exclude: /node_modules/, loaders: ['react-hot-loader/webpack'] }
    ]
  },
  resolve: {
    extensions: ['*', '.js'],
    modules: [ __dirname + '/node_modules', __dirname + '/src', __dirname + '/../out/client/compileJs/dest'],
    alias: {
      'react-dom': '@hot-loader/react-dom',
    }
  },
  output: {
    path: __dirname + '/../out/client/webpack/dest',
    publicPath: '/',
    filename: 'bundle.js'
  },
  devServer: {
    contentBase: './resources',
    hot: true
  }
};
