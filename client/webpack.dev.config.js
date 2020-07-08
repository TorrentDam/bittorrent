var HtmlWebpackPlugin = require('html-webpack-plugin');

module.exports = {
  mode: "production",
  entry: [
    'react-hot-loader',
    __dirname + '/src/index.js'
  ],
  resolve: {
    modules: [
      __dirname + '/node_modules',
      __dirname + '/../out/client/compileJs/dest'
    ],
    alias: {
      'react-dom': '@hot-loader/react-dom',
    }
  },
  module: {
    rules: [
      { test: /\.js$/, exclude: /node_modules/, loaders: ['react-hot-loader/webpack'] }
    ]
  },
  output: {
    path: __dirname + '/../out/client/webpack/dest',
    publicPath: '/',
    filename: 'bundle.js'
  },
  devServer: {
    hot: true,
    watchOptions: {
      poll: 200
    }
  },
  plugins: [
    new HtmlWebpackPlugin({
      templateParameters: {
        config: {
          serverUrl: '25e5f366-a664-487a-8bb2-33f106743c8a.pub.cloud.scaleway.com',
          useEncryption: true
          // serverUrl: 'localhost:9999',
          // useEncryption: false
        }
      }
    })
  ]
};
