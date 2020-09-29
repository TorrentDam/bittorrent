var HtmlWebpackPlugin = require('html-webpack-plugin');

module.exports = {
  mode: "production",
  entry: [
    __dirname + '/src/index.js'
  ],
  resolve: {
    extensions: ['.js'],
    modules: [
      __dirname + '/public',
      __dirname + '/node_modules',
      __dirname + '/../out/webapp/fullOpt/dest'
    ]
  },
  module: {
    rules: [
      { test: /\.svg$/, use: ['@svgr/webpack'] }
    ]
  },
  plugins: [
    new HtmlWebpackPlugin({
      templateParameters: {
        config: {
          serverUrl: 'bittorrent-server.herokuapp.com',
          useEncryption: true
        }
      }
    })
  ]
};
