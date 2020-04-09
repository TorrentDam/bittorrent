var HtmlWebpackPlugin = require('html-webpack-plugin');

module.exports = {
  mode: "production",
  entry: [
    __dirname + '/src/index.js'
  ],
  resolve: {
    extensions: ['.js'],
    modules: [
      __dirname + '/node_modules',
      __dirname + '/../out/client/fullOpt/dest'
    ]
  },
  plugins: [
    new HtmlWebpackPlugin({
      templateParameters: {
        serverUrl: '25e5f366-a664-487a-8bb2-33f106743c8a.pub.cloud.scaleway.com'
      }
    })
  ]
};
