const webpack = require('webpack');

module.exports = {
  entry: '../out/client/fastOpt/dest/out.js',
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
    path: __dirname + '/../out/client/webpack/dest',
    publicPath: '/',
    filename: 'bundle.js'
  },
  plugins: [
    new webpack.HotModuleReplacementPlugin()
  ],
  devServer: {
    contentBase: './resources',
    hot: true,
    watchOptions: {
      aggregateTimeout: 300,
      poll: 1000
    }
  }
};
